package com.torfilx.tv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Generates a small, genuinely decodable H.264 MP4 on the device.
 *
 * Playback cannot be verified with a fake: the point is to prove that the real decoder, the real
 * data source and the real progress reporting work together. Encoding the clip on the device (rather
 * than shipping a binary fixture) guarantees the file matches what this device can decode.
 */
object TestVideoFactory {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val FRAME_RATE = 25
    private const val BITRATE = 1_500_000
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_US = 10_000L

    /** Writes a [durationSeconds]-long clip to [output] and returns it. */
    fun createVideo(output: File, durationSeconds: Int = 6): File {
        val totalFrames = FRAME_RATE * durationSeconds
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        var frameIndex = 0
        var inputDone = false
        var outputDone = false

        val frame = ByteArray(WIDTH * HEIGHT * 3 / 2)

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer = encoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    if (frameIndex < totalFrames) {
                        fillFrame(frame, frameIndex)
                        inputBuffer.put(frame)
                        val presentationTimeUs = frameIndex * 1_000_000L / FRAME_RATE
                        encoder.queueInputBuffer(inputIndex, 0, frame.size, presentationTimeUs, 0)
                        frameIndex++
                    } else {
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            frameIndex * 1_000_000L / FRAME_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    }
                }
            }

            when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                else -> if (outputIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(outputIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        encoder.stop()
        encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        return output
    }

    /** A moving horizontal gradient — enough motion that the encoder cannot collapse it to nothing. */
    private fun fillFrame(frame: ByteArray, frameIndex: Int) {
        val ySize = WIDTH * HEIGHT
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                frame[y * WIDTH + x] = ((x + frameIndex * 4) % 255).toByte()
            }
        }
        // Neutral chroma (grey) keeps the generator simple and the file small.
        java.util.Arrays.fill(frame, ySize, frame.size, 128.toByte())
    }
}
