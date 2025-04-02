package com.example.screepcap.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
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
import android.view.Surface;

import com.example.screepcap.manager.AudioRecordManager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 屏幕录制服务
 * 使用 MediaCodec 进行视频编码，MediaMuxer 生成 MP4 文件
 */
public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private static final String CHANNEL_ID = "ScreenRecording";
    private static final int NOTIFICATION_ID = 1;
    // Handler 消息类型
    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    // MediaCodec 编码超时时间，单位：微秒
    private static final int TIMEOUT_US = 10000;

    // 屏幕捕获相关
    private MediaProjection mediaProjection;    // 用于获取屏幕内容
    private VirtualDisplay virtualDisplay;      // 虚拟显示器，用于承载屏幕内容
    
    // 视频编码相关
    private MediaCodec encoder;                 // 视频编码器
    private MediaMuxer mediaMuxer;             // 视频封装器，用于生成 MP4 文件
    private Surface inputSurface;              // 输入surface，接收屏幕内容
    
    // 音频相关
    private int audioTrackIndex = -1;        // 音频轨道索引

    // 线程和Handler
    private HandlerThread handlerThread;        // 录制线程
    private Handler recordHandler;              // 录制线程的Handler
    private Handler mainHandler;                // 主线程Handler
    
    // 屏幕参数
    private int screenWidth;                    // 屏幕宽度
    private int screenHeight;                   // 屏幕高度
    private int screenDensity;                  // 屏幕密度
    
    // 状态标志
    private boolean isRecording;                // 是否正在录制
    private int videoTrackIndex = -1;          // 视频轨道索引
    private boolean muxerStarted = false;       // 是否已开始封装视频
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);  // 是否正在处理视频帧
    private String outputPath;                  // 输出文件路径

    // 用于Activity绑定服务
    private final IBinder binder = new RecordBinder();
    
    // MediaProjection 回调，用于处理屏幕录制被系统取消的情况
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopRecording();
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
     * 设置屏幕录制配置
     * @param projection MediaProjection 对象
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param density 屏幕密度
     */
    public void setConfig(MediaProjection projection, int width, int height, int density) {
        mediaProjection = projection;
        screenWidth = width;
        screenHeight = height;
        screenDensity = density;
        mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);

        // 初始化音频录制
        AudioRecordManager.Controller.init(this, mediaProjection, new AudioRecordManager.AudioDataCallback() {
            @Override
            public void onAudioData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) {
                if (muxerStarted && audioTrackIndex >= 0) {
                    mediaMuxer.writeSampleData(audioTrackIndex, buffer, bufferInfo);
                }
            }
        });
    }

    /**
     * 开始屏幕录制
     * @return 是否成功开始录制
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
        if (prepareEncoder()) {
            createVirtualDisplay();
            isRecording = true;
            isProcessing.set(true);
            // 启动音频录制
            AudioRecordManager.Controller.start();
            startEncodingLoop();
        }
    }

    /**
     * 处理停止录制消息
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        
        // First signal that recording should stop
        isRecording = false;
        
        // Stop audio recording first
        AudioRecordManager.Controller.stop();
        
        // Wait for any pending frames to be encoded
        try {
            if (encoder != null) {
                encoder.signalEndOfInputStream();
                // Give some time for the encoder to process remaining frames
                Thread.sleep(500);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error signaling end of stream", e);
        }
        
        // Now we can stop processing
        isProcessing.set(false);
        
        // Release resources
        releaseEncoderResources();
        
        mainHandler.post(() -> stopForeground(true));
    }

    /**
     * 准备视频编码器
     * @return 是否成功准备编码器
     */
    private boolean prepareEncoder() {
        String fileName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(new Date()) + ".mp4";
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName);
        outputPath = file.getAbsolutePath();
        Log.d(TAG, "Output file: " + outputPath);

        try {
            // 准备视频编码器
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 
                    screenWidth, screenHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 5 * 1024 * 1024);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "prepareEncoder error: " + e.getMessage());
            releaseEncoderResources();
            return false;
        }
    }

    /**
     * 创建虚拟显示器
     */
    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecording",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
        );
    }

    /**
     * 启动视频编码线程
     */
    private void startEncodingLoop() {
        new Thread(() -> {
            try {
                while (isProcessing.get()) {
                    if (!encodeFrame()) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in encoding loop", e);
            } finally {
                releaseEncoderResources();
                Log.i(TAG, "Encoding thread finished");
            }
        }, "EncodingThread").start();
    }

    /**
     * 编码视频帧
     * @return 是否成功编码帧
     */
    private boolean encodeFrame() {
        if (encoder == null || !isRecording) {
            return false;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

        if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return true;
        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (muxerStarted) {
                return false;
            }
            MediaFormat newFormat = encoder.getOutputFormat();
            videoTrackIndex = mediaMuxer.addTrack(newFormat);
            
            // 添加音频轨道
            MediaFormat audioFormat = AudioRecordManager.Controller.getFormat();
            if (audioFormat != null) {
                audioTrackIndex = mediaMuxer.addTrack(audioFormat);
            }
            
            mediaMuxer.start();
            muxerStarted = true;
            return true;
        } else if (outputBufferId < 0) {
            Log.w(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + outputBufferId);
            return true;
        }

        ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
        if (encodedData == null) {
            return false;
        }

        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            bufferInfo.size = 0;
        }

        if (bufferInfo.size > 0 && muxerStarted) {
            encodedData.position(bufferInfo.offset);
            encodedData.limit(bufferInfo.offset + bufferInfo.size);
            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
        }

        encoder.releaseOutputBuffer(outputBufferId, false);
        return true;
    }

    /**
     * 释放视频编码资源
     */
    private void releaseEncoderResources() {
        Log.d(TAG, "releaseEncoderResources");
        muxerStarted = false;
        videoTrackIndex = -1;
        audioTrackIndex = -1;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "releaseEncoderResources encoder error: " + e.getMessage());
            }
            encoder = null;
        }

        // 释放音频资源
        AudioRecordManager.Controller.release();

        if (mediaMuxer != null) {
            try {
                if (muxerStarted) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "releaseEncoderResources muxer error: " + e.getMessage());
            }
            mediaMuxer = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
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
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 创建通知
     * @return 通知对象
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopRecording();
        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(mediaProjectionCallback);
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }
}
