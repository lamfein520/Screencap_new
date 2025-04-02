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

    // 单例实例
    private static volatile AudioRecordManager instance;
    
    private AudioRecord audioRecord;      // 音频录制器
    private MediaCodec audioEncoder;      // 音频编码器
    private Thread recordingThread;       // 录制线程
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private AudioDataCallback callback;   // 数据回调接口
    private Context context;             // 上下文，用于权限检查
    private MediaProjection mediaProjection; // 用于内录

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
        void onAudioData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo);
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
        // 检查录音权限
        if (!checkAudioPermission()) {
            Log.e(TAG, "No audio permissions");
            return false;
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return false;
        }

        try {
            // 配置内录
            AudioPlaybackCaptureConfiguration config = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
            }

            // 初始化音频录制器
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(BUFFER_SIZE)
                        .setAudioPlaybackCaptureConfig(config)
                        .build();
            }

            // 检查音频录制器是否初始化成功
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                release();
                return false;
            }

            // 初始化音频编码器
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 2); // 2 channels for stereo
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);

            audioEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

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
        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        try {
            audioRecord.startRecording();
            audioEncoder.start();
            isRecording.set(true);

            // 启动录制线程
            recordingThread = new Thread(this::recordingLoop, "AudioRecordThread");
            recordingThread.start();
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
        if (audioEncoder != null) {
            return audioEncoder.getOutputFormat();
        }
        return null;
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
         * @param callback 音频数据回调
         * @return 是否初始化成功
         */
        public static boolean init(Context context, MediaProjection projection, AudioDataCallback callback) {
            AudioRecordManager manager = getInstance();
            manager.setContext(context, projection);
            manager.setCallback(callback);
            return manager.prepare();
        }

        /**
         * 开始录制
         */
        public static void start() {
            getInstance().startRecording();
        }

        /**
         * 停止录制
         */
        public static void stop() {
            getInstance().stopRecording();
        }

        /**
         * 获取音频格式
         */
        public static MediaFormat getFormat() {
            return getInstance().getAudioFormat();
        }

        /**
         * 释放资源
         */
        public static void release() {
            getInstance().release();
        }
    }
}
