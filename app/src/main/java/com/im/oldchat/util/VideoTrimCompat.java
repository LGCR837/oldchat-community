package com.im.oldchat.util;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
final class VideoTrimCompat {
    private VideoTrimCompat() {
    }

    static boolean trimVideo(File input, File output, long endUs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) {
                return false;
            }
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] indexMap = new int[trackCount];
            int maxBufferSize = 1024 * 1024;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                indexMap[i] = muxer.addTrack(format);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    int size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    if (size > maxBufferSize) {
                        maxBufferSize = size;
                    }
                }
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(input.getAbsolutePath());
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotation != null) {
                    try {
                        muxer.setOrientationHint(Integer.parseInt(rotation));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(maxBufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int track = 0; track < trackCount; track++) {
                extractor.selectTrack(track);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        break;
                    }
                    long timeUs = extractor.getSampleTime();
                    if (endUs > 0 && timeUs > endUs) {
                        break;
                    }
                    info.offset = 0;
                    info.size = size;
                    info.flags = 0;
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    info.presentationTimeUs = timeUs;
                    muxer.writeSampleData(indexMap[track], buffer, info);
                    extractor.advance();
                }
                extractor.unselectTrack(track);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
