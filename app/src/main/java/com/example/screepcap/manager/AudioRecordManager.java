package com.example.screepcap.manager;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频录制管理器
 * 负责音频的采集、编码和输出
 * 使用单例模式确保全局只有一个音频录制实例
 */
public class AudioRecordManager {
    private static final String TAG = "AudioRecordManager";

    // 音频配置参数
    private static final int SAMPLE_RATE = 44100;      // 采样率
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;  // 立体声
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT; // 16位PCM格式
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    // 编码参数
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AUDIO_BITRATE = 192000;   // 比特率
    private static final int MAX_INPUT_SIZE = 16384;

    // 音频源类型
    private static final int AUDIO_SOURCE_BELOW_Q = MediaRecorder.AudioSource.REMOTE_SUBMIX;

    // 单例实例
    private static volatile AudioRecordManager instance;
    
    private AudioRecord audioRecord;      // 音频录制器
    private MediaCodec audioEncoder;      // 音频编码器
    private Thread recordingThread;       // 录制线程
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPrepared = new AtomicBoolean(false);  // 添加准备状态标志
    private AudioDataCallback callback;   // 数据回调接口
    private Context context;             // 上下文，用于权限检查
    private MediaProjection mediaProjection; // 用于内录
    private static MediaFormat format;          // 音频格式

    /**
     * 私有构造函数，防止外部创建实例
     */
    private AudioRecordManager() {
        // 私有构造函数
    }

    /**
     * 获取AudioRecordManager单例
     * 使用双重检查锁定模式
     * @return AudioRecordManager实例
     */
    public static AudioRecordManager getInstance() {
        if (instance == null) {
            synchronized (AudioRecordManager.class) {
                if (instance == null) {
                    instance = new AudioRecordManager();
                }
            }
        }
        return instance;
    }

    /**
     * 设置上下文和MediaProjection
     * @param context 应用上下文
     * @param projection MediaProjection对象
     */
    private void setContext(Context context, MediaProjection projection) {
        this.context = context;
        this.mediaProjection = projection;
    }

    /**
     * 检查录音权限
     * @return 是否有录音权限
     */
    private boolean checkAudioPermission() {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot check permissions");
            return false;
        }
        
        // 检查基本录音权限
        boolean hasBasicPermission = ContextCompat.checkSelfPermission(context, 
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        
        // 检查修改音频设置权限
        // TODO 这个权限目前没有用到，后续可能需要用来调节音量
        boolean hasModifySettings = ContextCompat.checkSelfPermission(context, 
                Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED;

        return hasBasicPermission && hasModifySettings;
    }

    /**
     * 音频数据回调接口
     */
    public interface AudioDataCallback {
        /**
         * 当有新的音频数据可用时回调
         * @param buffer 音频数据
         * @param bufferInfo 音频数据信息
         */
        void onAudioData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo);

        /**
         * 当音频格式发生变化时回调
         * @param format 新的音频格式
         */
        default void onFormatChanged(MediaFormat format) {}
    }

    /**
     * 设置音频数据回调
     * @param callback 回调接口实现
     */
    private void setCallback(AudioDataCallback callback) {
        this.callback = callback;
    }

    /**
     * 初始化音频录制和编码器
     * @return 是否初始化成功
     */
    private boolean prepare() {
        if (isPrepared.get()) {
            Log.d(TAG, "Already prepared");
            return true;
        }

        // 检查录音权限
        if (!checkAudioPermission()) {
            Log.e(TAG, "No audio permissions");
            return false;
        }

        try {
            // 初始化音频录制器
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build();

            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(BUFFER_SIZE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 及以上使用 AudioPlaybackCaptureConfiguration
                if (mediaProjection == null) {
                    Log.e(TAG, "MediaProjection is null, required for Android 10 and above");
                    return false;
                }
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                builder.setAudioPlaybackCaptureConfig(config);
            } else {
                // Android 10 以下使用 REMOTE_SUBMIX
                builder.setAudioSource(AUDIO_SOURCE_BELOW_Q);
            }

            audioRecord = builder.build();
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                release();
                return false;
            }

            // 初始化音频编码器
            format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2); // 2 channels for stereo
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);

            audioEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // 通知音频格式已准备好
            if (callback != null) {
                callback.onFormatChanged(format);
            }

            isPrepared.set(true);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare audio recorder: " + e.getMessage());
            release();
            return false;
        }
    }

    /**
     * 开始录制音频
     */
    private void startRecording() {
        // 确保已经准备好
        if (!isPrepared.get()) {
            Log.e(TAG, "Cannot start recording - not prepared");
            return;
        }

        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        if (audioRecord == null || audioEncoder == null) {
            Log.e(TAG, "Cannot start recording - recorder or encoder is null");
            return;
        }

        try {
            audioRecord.startRecording();
            audioEncoder.start();
            isRecording.set(true);

            // 启动录制线程
            recordingThread = new Thread(() -> recordingLoop(), "AudioRecordThread");
            recordingThread.start();
            Log.i(TAG, "Audio recording started successfully");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage());
            stopRecording();
        }
    }

    /**
     * 停止录制音频
     */
    private void stopRecording() {
        isRecording.set(false);
        isPrepared.set(false);  // 重置准备状态
        
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for recording thread");
            }
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to stop AudioRecord: " + e.getMessage());
            }
        }

        if (audioEncoder != null) {
            try {
                audioEncoder.signalEndOfInputStream();
                audioEncoder.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to stop MediaCodec: " + e.getMessage());
            }
        }
    }

    /**
     * 释放资源
     */
    private void release() {
        stopRecording();

        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }

        if (audioEncoder != null) {
            audioEncoder.release();
            audioEncoder = null;
        }
    }

    /**
     * 获取音频编码格式
     * @return 音频MediaFormat，如果编码器未初始化则返回null
     */
    private MediaFormat getAudioFormat() {
        return format;
    }

    /**
     * 录制循环
     * 从AudioRecord读取数据并送入编码器
     */
    private void recordingLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (isRecording.get()) {
            // 读取音频数据
            int readSize = audioRecord.read(buffer, 0, BUFFER_SIZE);
            if (readSize > 0) {
                // 将数据送入编码器
                int inputBufferId = audioEncoder.dequeueInputBuffer(-1);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferId);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(buffer, 0, readSize);
                        audioEncoder.queueInputBuffer(inputBufferId, 0, readSize, 
                                System.nanoTime() / 1000, 0);
                    }
                }
            }

            // 获取编码后的数据
            int outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            while (outputBufferId >= 0) {
                ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferId);
                if (outputBuffer != null && callback != null) {
                    callback.onAudioData(outputBuffer, bufferInfo);
                }
                audioEncoder.releaseOutputBuffer(outputBufferId, false);
                outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        }
    }

    /**
     * 提供给外部的录制控制接口
     */
    public static class Controller {
        /**
         * 初始化录制
         * @param context 应用上下文
         * @param projection MediaProjection对象
         * @param dataCallback 音频数据回调
         * @return 是否初始化成功
         */
        public static boolean init(Context context, MediaProjection projection, AudioDataCallback dataCallback) {
            AudioRecordManager manager = getInstance();
            manager.setContext(context, projection);
            manager.setCallback(dataCallback);
            return manager.prepare();
        }

        /**
         * 开始录制
         */
        public static void start() {
            AudioRecordManager manager = getInstance();
            if (!manager.isPrepared.get() && !manager.prepare()) {
                Log.e(TAG, "Failed to prepare audio recorder");
                return;
            }
            manager.startRecording();
        }

        /**
         * 停止录制
         */
        public static void stop() {
            AudioRecordManager manager = getInstance();
            manager.stopRecording();
        }

        /**
         * 获取音频格式
         */
        public static MediaFormat getFormat() {
            return format;
        }

        /**
         * 检查音频格式是否准备好
         * @return 如果音频格式已准备好返回true，否则返回false
         */
        public static boolean isFormatReady() {
            return format != null;
        }

        /**
         * 释放资源
         */
        public static void release() {
            AudioRecordManager manager = getInstance();
            manager.release();
        }
    }
}
