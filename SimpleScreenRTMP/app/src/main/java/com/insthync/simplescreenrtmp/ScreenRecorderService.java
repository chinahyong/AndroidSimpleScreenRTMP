package com.insthync.simplescreenrtmp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Surface;

import net.butterflytv.rtmp_client.RTMPMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenRecorderService extends Service {
    private final String TAG = "ScreenRecorderService";
    public static final String ACTION_STOP = "ACTION_STOP";
    // Default Video Record Setting
    public static final int DEFAULT_SCREEN_WIDTH = 640;
    public static final int DEFAULT_SCREEN_HEIGHT = 480;
    public static final int DEFAULT_SCREEN_DPI = 320;
    public static final int DEFAULT_VIDEO_BITRATE = 1024 * 1000;
    public static final int DEFAULT_VIDEO_FPS = 25;
    // Video Record Setting
    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int VIDEO_IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final int VIDEO_TIMEOUT_US = 10000;

    // Default Audio Record Setting
    public static final int DEFAULT_AUDIO_RECORDER_SOURCE = MediaRecorder.AudioSource.DEFAULT;
    public static final int DEFAULT_AUDIO_SAMPLE_RATE = 44100;
    public static final int DEFAULT_AUDIO_BITRATE = 32 * 1024;
    // Audio Record Setting
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_MAX_INPUT_SIZE = 8820;
    private static final int AUDIO_TIMEOUT_US = 10000;
    private static final int AUDIO_RECORD_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RTMP_ADDRESS = "rtmp_address";

    public static final String EXTRA_SCREEN_WIDTH = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_SCREEN_DPI = "screen_dpi";
    public static final String EXTRA_VIDEO_BITRATE = "video_bitrate";

    public static final String EXTRA_AUDIO_RECORDER_SOURCE = "audio_recorder_source";
    public static final String EXTRA_AUDIO_SAMPLE_RATE = "audio_sample_rate";
    public static final String EXTRA_AUDIO_BITRATE = "audio_bitrate";

    private final int NT_ID_CASTING = 0;

    private MediaProjectionManager mMediaProjectionManager;
    private String mRtmpAddresss;

    private int mResultCode;
    private Intent mResultData;

    private int mSelectedVideoWidth;
    private int mSelectedVideoHeight;
    private int mSelectedVideoDpi;
    private int mSelectedVideoBitrate;

    private int mSelectedAudioRecordSource;
    private int mSelectedAudioSampleRate;
    private int mSelectedAudioBitrate;

    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;

    private AudioRecord audioRecord;
    private byte[] audioBuffer;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mAudioBufferInfo;

    private RTMPMuxer mRTMPMuxer;
    private long mStartTime;
    private long mVideoTryingAgainTime;
    private boolean mIsSetHeader;

    private IntentFilter mBroadcastIntentFilter;
    private Handler mDrainVideoEncoderHandler = new Handler();
    private Handler mDrainAudioEncoderHandler = new Handler();
    private Handler mRecordAudioHandler = new Handler();

    private Runnable mDrainVideoEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainVideoEncoder();
        }
    };

    private Runnable mDrainAudioEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainAudioEncoder();
        }
    };

    private Runnable mRecordAudioRunnable = new Runnable() {
        @Override
        public void run() {
            recordAudio();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action == null) {
                return;
            }
            if (ACTION_STOP.equals(action)) {
                stopScreenCapture();
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(ACTION_STOP);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy service");
        stopScreenCapture();
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_NOT_STICKY;

        mRtmpAddresss = intent.getStringExtra(EXTRA_RTMP_ADDRESS);
        mResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        mResultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Log.d(TAG, "RTMP Address: " + mRtmpAddresss);

        if (mRtmpAddresss == null)
            return START_NOT_STICKY;

        if (mResultCode != Activity.RESULT_OK || mResultData == null) {
            Log.e(TAG, "Failed to start service, mResultCode: " + mResultCode + ", mResultData: " + mResultData);
            return START_NOT_STICKY;
        }

        mSelectedVideoWidth = intent.getIntExtra(EXTRA_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH);
        mSelectedVideoHeight = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, DEFAULT_SCREEN_HEIGHT);
        mSelectedVideoDpi = intent.getIntExtra(EXTRA_SCREEN_DPI, DEFAULT_SCREEN_DPI);
        mSelectedVideoBitrate = intent.getIntExtra(EXTRA_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE);

        mSelectedAudioRecordSource = intent.getIntExtra(EXTRA_AUDIO_RECORDER_SOURCE, DEFAULT_AUDIO_RECORDER_SOURCE);
        mSelectedAudioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, DEFAULT_AUDIO_SAMPLE_RATE);
        mSelectedAudioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE);

        if (!startScreenCapture()) {
            Log.e(TAG, "Failed to start capture screen");
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showNotification() {
        final Intent notificationIntent = new Intent(ACTION_STOP);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        final NotificationCompat.Action actionStop = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent).build();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.casting_screen))
                .addAction(actionStop);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }

    private boolean startScreenCapture() {
        Log.d(TAG, "mResultCode: " + mResultCode + ", mResultData: " + mResultData);
        if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            startRecording();
            showNotification();
            return true;
        }
        return false;
    }

    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");

        mStartTime = System.currentTimeMillis();
        mVideoTryingAgainTime = 0;
        mIsSetHeader = false;

        prepareVideoEncoder();
        prepareAudioEncoder();

        mRTMPMuxer = new RTMPMuxer();
        int result = mRTMPMuxer.open(mRtmpAddresss, mSelectedVideoWidth, mSelectedVideoHeight);
        Log.d(TAG, "RTMP_URL open result: " + result);

        // Start the video input.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", mSelectedVideoWidth,
                mSelectedVideoHeight, mSelectedVideoDpi, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        // Start the encoders
        drainVideoEncoder();
        drainAudioEncoder();
    }

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, mSelectedVideoWidth, mSelectedVideoHeight);
        int frameRate = DEFAULT_VIDEO_FPS;

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedVideoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial video encoder, e: " + e);
            releaseEncoders();
        }
    }

    private void prepareAudioEncoder() {
        int minBufferSize = AudioRecord.getMinBufferSize(mSelectedAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT);
        audioRecord = new AudioRecord(mSelectedAudioRecordSource, mSelectedAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT, minBufferSize * 5);
        audioBuffer = new byte[mSelectedAudioSampleRate / 10 * 2];

        if (AudioRecord.STATE_INITIALIZED != audioRecord.getState()) {
            Log.e(TAG, "audioRecord.getState()!=AudioRecord.STATE_INITIALIZED!");
            return;
        }

        audioRecord.startRecording();
        recordAudio();

        mAudioBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, mSelectedAudioSampleRate, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedAudioBitrate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE);

        try {
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial audio encoder, e: " + e);
            releaseEncoders();
        }
    }

    private boolean recordAudio() {
        mRecordAudioHandler.removeCallbacks(mRecordAudioRunnable);

        if (mAudioEncoder != null) {
            int timestamp = (int) (System.currentTimeMillis() - mStartTime);
            int size = audioRecord.read(audioBuffer, 0, audioBuffer.length);
            if (size > 0) {
                int index = mAudioEncoder.dequeueInputBuffer(-1);
                if (index >= 0) {
                    ByteBuffer dstAudioEncoderIBuffer = mAudioEncoder.getInputBuffer(index);
                    dstAudioEncoderIBuffer.position(0);
                    dstAudioEncoderIBuffer.put(audioBuffer, 0, audioBuffer.length);
                    mAudioEncoder.queueInputBuffer(index, 0, audioBuffer.length, timestamp, 0);
                }
            }
        }

        mRecordAudioHandler.postDelayed(mRecordAudioRunnable, 10);
        return true;
    }

    private boolean drainVideoEncoder() {
        mDrainVideoEncoderHandler.removeCallbacks(mDrainVideoEncoderRunnable);

        if (mVideoEncoder != null) {
            while (true) {
                int index = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, VIDEO_TIMEOUT_US);

                int timestamp = (int) (System.currentTimeMillis() - mStartTime);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Video Format changed " + mVideoEncoder.getOutputFormat());
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (mVideoTryingAgainTime == 0)
                        mVideoTryingAgainTime = System.currentTimeMillis();
                    //Log.d(TAG, "Contents are not ready, trying again...");
                    break;
                } else if (index >= 0) {
                    if (mVideoTryingAgainTime > 0) {
                        long tryAgainAfterTime = System.currentTimeMillis() - mVideoTryingAgainTime;
                        Log.d(TAG, "Tried again after " + tryAgainAfterTime + " ms");
                        mVideoTryingAgainTime = 0;
                    }
                    ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(index);
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                    byte[] bytes = new byte[encodedData.remaining()];
                    encodedData.get(bytes);

                    if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Pulling codec config data
                        if (!mIsSetHeader) {
                            writeVideoMuxer(true, timestamp, bytes);
                            mIsSetHeader = true;
                        }
                        mVideoBufferInfo.size = 0;
                    }

                    if (mVideoBufferInfo.size > 0) {
                        writeVideoMuxer(false, timestamp, bytes);
                    }

                    mVideoEncoder.releaseOutputBuffer(index, false);
                }
            }
        }

        mDrainVideoEncoderHandler.postDelayed(mDrainVideoEncoderRunnable, 10);
        return true;
    }

    private boolean drainAudioEncoder() {
        mDrainAudioEncoderHandler.removeCallbacks(mDrainAudioEncoderRunnable);

        if (mAudioEncoder != null) {
            while (true) {
                int index = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, AUDIO_TIMEOUT_US);

                int timestamp = (int) (System.currentTimeMillis() - mStartTime);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Audio Format changed " + mAudioEncoder.getOutputFormat());
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (index >= 0) {
                    ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(index);
                    encodedData.position(mAudioBufferInfo.offset);
                    encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

                    byte[] bytes = new byte[encodedData.remaining()];
                    encodedData.get(bytes);

                    if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Pulling codec config data
                        if (!mIsSetHeader) {
                            writeAudioMuxer(true, timestamp, bytes);
                            mIsSetHeader = true;
                        }
                        mAudioBufferInfo.size = 0;
                    }

                    if (mAudioBufferInfo.size > 0) {
                        writeAudioMuxer(false, timestamp, bytes);
                    }

                    mAudioEncoder.releaseOutputBuffer(index, false);
                }
            }
        }

        mDrainAudioEncoderHandler.postDelayed(mDrainAudioEncoderRunnable, 10);
        return true;
    }

    private void writeVideoMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        int rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeVideo(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write video result: " + writeResult + " is header: " + isHeader);
    }

    private void writeAudioMuxer(boolean isHeader, int timestamp, byte[] bytes) {
        int rtmpConnectionState = mRTMPMuxer != null ? mRTMPMuxer.isConnected() : 0;
        Log.d(TAG, "RTMP connection state: " + rtmpConnectionState + " timestamp: " + timestamp + " byte[] length: " + bytes.length);
        int writeResult = mRTMPMuxer.writeAudio(bytes, 0, bytes.length, timestamp);
        Log.d(TAG, "RTMP write audio result: " + writeResult + " is header: " + isHeader);
    }

    private void stopScreenCapture() {
        dismissNotification();
        releaseEncoders();
    }

    private void releaseEncoders() {
        mDrainVideoEncoderHandler.removeCallbacks(mDrainVideoEncoderRunnable);
        mDrainAudioEncoderHandler.removeCallbacks(mDrainAudioEncoderRunnable);
        mRecordAudioHandler.removeCallbacks(mRecordAudioRunnable);

        if (mRTMPMuxer != null) {
            mRTMPMuxer.close();
            mRTMPMuxer = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        mVideoBufferInfo = null;
    }
}