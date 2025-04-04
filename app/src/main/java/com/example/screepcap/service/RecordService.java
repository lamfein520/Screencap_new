package com.example.screepcap.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.example.screepcap.manager.AudioRecordManager;
import com.example.screepcap.manager.ScreenRecordManager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 屏幕录制服务
 * 使用 ScreenRecordManager 进行视频录制，AudioRecordManager 进行音频录制
 * MediaMuxer 用于生成 MP4 文件
 */
public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private static final String CHANNEL_ID = "ScreenRecording";
    private static final int NOTIFICATION_ID = 1;
    
    // Handler 消息类型
    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FORMAT_CHANGED = 2;  // 新增消息类型

    // 线程和Handler
    private HandlerThread handlerThread;        // 录制线程
    private Handler recordHandler;              // 录制线程的Handler
    private Handler mainHandler;                // 主线程Handler
    
    // 视频封装相关
    private MediaMuxer mediaMuxer;             // 视频封装器，用于生成 MP4 文件
    private int videoTrackIndex = -1;          // 视频轨道索引
    private int audioTrackIndex = -1;          // 音频轨道索引
    private boolean muxerStarted = false;       // 是否已开始封装视频
    private String outputPath;                  // 输出文件路径
    private MediaFormat pendingVideoFormat;     // 待处理的视频格式
    
    // 屏幕录制相关
    private MediaProjection mediaProjection;
    private boolean isRecording = false;

    // 用于Activity绑定服务
    private final IBinder binder = new RecordBinder();

    // MediaProjection 回调，用于处理屏幕录制被系统取消的情况
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            mainHandler.post(() -> {
                Log.i(TAG, "MediaProjection stopped by system");
                stopRecording();
                stopSelf();
            });
        }
    };

    /**
     * Binder类，用于Activity和Service通信
     */
    public class RecordBinder extends Binder {
        public RecordService getService() {
            return RecordService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // 初始化录制线程
        handlerThread = new HandlerThread("RecordingThread");
        handlerThread.start();
        recordHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_START_RECORDING:
                        handleStartRecording();
                        break;
                    case MSG_STOP_RECORDING:
                        handleStopRecording();
                        break;
                    case MSG_FORMAT_CHANGED:
                        handleFormatChanged((MediaFormat) msg.obj);
                        break;
                }
            }
        };
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * 设置屏幕录制配置,初始化视频和音频录制管理器
     * @param projection MediaProjection 对象
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param density 屏幕密度
     */
    public void setConfig(MediaProjection projection, int width, int height, int density) {
        this.mediaProjection = projection;
        
        // 注册MediaProjection回调
        mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);
        
        // 初始化视频录制管理器
        ScreenRecordManager.Controller.init(this, projection, width, height, density);
        ScreenRecordManager.Controller.setCallback(new ScreenRecordManager.VideoDataCallback() {
            @Override
            public void onVideoData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) {
                if (!muxerStarted || videoTrackIndex < 0) {
                    Log.w(TAG, "Muxer not started or invalid video track index");
                    return;
                }
                try {
                    mediaMuxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write video data to muxer", e);
                }
//                setupMuxerIfReady();
            }

            @Override
            public void onFormatChanged(MediaFormat format) {
                if (muxerStarted) {
                    Log.w(TAG, "Muxer already started, ignoring format change");
                    return;
                }
                
                if (format != null) {
                    // 视频格式变更
                    pendingVideoFormat = format;
                    Log.d(TAG, "Video format changed: " + format);
                }
                
                setupMuxerIfReady();
            }
        });

        // 初始化音频录制管理器
        AudioRecordManager.Controller.init(this, projection, new AudioRecordManager.AudioDataCallback() {
            @Override
            public void onAudioData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) {
                if (!muxerStarted || audioTrackIndex < 0) {
                    Log.w(TAG, "Muxer not started or invalid audio track index");
                    return;
                }
                try {
                    mediaMuxer.writeSampleData(audioTrackIndex, buffer, bufferInfo);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write audio data to muxer", e);
                }
            }

            @Override
            public void onFormatChanged(MediaFormat format) {
                // 音频格式在视频格式变更时一起处理
            }
        });
    }

    /**
     * 开始屏幕录制
     */
    public boolean startRecording() {
        if (!isRecording) {
            recordHandler.sendEmptyMessage(MSG_START_RECORDING);
            return true;
        }
        return false;
    }

    /**
     * 停止屏幕录制
     */
    public void stopRecording() {
        if (isRecording) {
            recordHandler.sendEmptyMessage(MSG_STOP_RECORDING);
        }
    }

    /**
     * 处理开始录制消息
     */
    private void handleStartRecording() {
        Log.d(TAG, "handleStartRecording");
        try {
            // 准备输出文件
            setupOutputFile();

            // 开始视频录制
            ScreenRecordManager.Controller.start();
            
            // 开始音频录制
            AudioRecordManager.Controller.start();
            
            isRecording = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start recording", e);
            releaseRecording();
        }
    }

    /**
     * 处理停止录制消息
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        isRecording = false;
        
        // 停止音频录制
        AudioRecordManager.Controller.stop();
        
        // 停止视频录制
        ScreenRecordManager.Controller.stop();
        
        // 释放资源
        releaseRecording();
        mainHandler.post(() -> stopForeground(true));
    }

    /**
     * 处理视频格式变更
     */
    private void handleFormatChanged(MediaFormat format) {
        if (muxerStarted) {
            Log.w(TAG, "Muxer already started, ignoring format change");
            return;
        }
        
        try {
            // 添加视频轨道
            videoTrackIndex = mediaMuxer.addTrack(format);
            
            // 添加音频轨道
            MediaFormat audioFormat = AudioRecordManager.Controller.getFormat();
            if (audioFormat != null) {
                audioTrackIndex = mediaMuxer.addTrack(audioFormat);
                Log.d(TAG, "Added audio track: " + audioTrackIndex);
            }
            
            // 启动mediaMuxer
            mediaMuxer.start();
            muxerStarted = true;
            Log.d(TAG, "MediaMuxer started with video track: " + videoTrackIndex + ", audio track: " + audioTrackIndex);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup tracks", e);
            releaseRecording();
        }
    }

    /**
     * 如果视频和音频格式都准备好了，就设置并启动Muxer
     */
    private void setupMuxerIfReady() {
        if (muxerStarted) {
            return;
        }

        // 检查视频和音频格式是否都准备好
        if (pendingVideoFormat == null) {
            Log.d(TAG, "Waiting for video format...");
            return;
        }
        
        MediaFormat audioFormat = AudioRecordManager.Controller.getFormat();
        if (audioFormat == null) {
            Log.d(TAG, "Waiting for audio format...");
            return;
        }

        try {
            Log.i(TAG, "Both video and audio format ready, starting muxer");
            Log.d(TAG, "Video format: " + pendingVideoFormat);
            Log.d(TAG, "Audio format: " + audioFormat);
            
            videoTrackIndex = mediaMuxer.addTrack(pendingVideoFormat);
            audioTrackIndex = mediaMuxer.addTrack(audioFormat);
            Log.d(TAG, "Track indices - video: " + videoTrackIndex + ", audio: " + audioTrackIndex);
            
            mediaMuxer.start();
            muxerStarted = true;
            Log.i(TAG, "MediaMuxer started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup muxer", e);
            releaseRecording();
        }
    }

    /**
     * 准备输出文件
     */
    private void setupOutputFile() throws IOException {
        String fileName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName);
        outputPath = file.getAbsolutePath();
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenRecordings");
//        if (!outputDir.exists() && !outputDir.mkdirs()) {
//            throw new IOException("Failed to create output directory");
//        }
//        outputPath = new File(outputDir, fileName).getAbsolutePath();
        mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /**
     * 释放录制资源
     */
    private void releaseRecording() {
        if (mediaMuxer != null) {
            try {
                if (muxerStarted) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error while releasing MediaMuxer", e);
            }
            mediaMuxer = null;
        }
        
        muxerStarted = false;
        videoTrackIndex = -1;
        audioTrackIndex = -1;
        pendingVideoFormat = null;
        
        ScreenRecordManager.Controller.release();
        AudioRecordManager.Controller.release();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopRecording();
        
        // 释放MediaProjection
        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(mediaProjectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Screen recording service notification channel");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 创建前台服务通知
     */
    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Screen Recording")
                .setContentText("Recording in progress...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }
}
