/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.jmfext.media.protocol.portaudio;

import java.io.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.jmfext.media.protocol.*;
import org.jitsi.impl.neomedia.portaudio.*;
import org.jitsi.util.*;

/**
 * Implements <tt>PullBufferStream</tt> for PortAudio.
 *
 * @author Damian Minkov
 * @author Lyubomir Marinov
 */
public class PortAudioStream
    extends AbstractPullBufferStream
{
    /**
     * The <tt>Logger</tt> used by the <tt>PortAudioStream</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(PortAudioStream.class);

    /**
     * The indicator which determines whether audio quality improvement is
     * enabled for this <tt>PortAudioStream</tt> in accord with the preferences
     * of the user.
     */
    private final boolean audioQualityImprovement;

    /**
     * The number of bytes to read from a native PortAudio stream in a single
     * invocation. Based on {@link #framesPerBuffer}.
     */
    private int bytesPerBuffer;

    /**
     * The device identifier (the device UID, or if not available, the device
     * name) of the PortAudio device read through this
     * <tt>PullBufferStream</tt>.
     */
    private String deviceID = null;

    /**
     * The last-known <tt>Format</tt> of the media data made available by this
     * <tt>PullBufferStream</tt>.
     */
    private AudioFormat format;

    /**
     * The number of frames to read from a native PortAudio stream in a single
     * invocation.
     */
    private int framesPerBuffer;

    /**
     * The <tt>GainControl</tt> through which the volume/gain of captured media
     * is controlled.
     */
    private final GainControl gainControl;

    /**
     * Native pointer to a PaStreamParameters object.
     */
    private long inputParameters = 0;

    private final PortAudioSystem.PaUpdateAvailableDeviceListListener
        paUpdateAvailableDeviceListListener
            = new PortAudioSystem.PaUpdateAvailableDeviceListListener()
            {
                /**
                 * The device ID used, before and if available after the update.
                 * This String contains the deviceUID, or if not available, the
                 * deviceName.
                 * If set to null, then there was no device used before the
                 * update.
                 */
                private String deviceID = null;

                private boolean start = false;

                public void didPaUpdateAvailableDeviceList()
                    throws Exception
                {
                    synchronized (PortAudioStream.this)
                    {
                        try
                        {
                            waitWhileStreamIsBusy();
                            /*
                             * The stream should be closed. If it is not,
                             * then something else happened in the meantime
                             * and we cannot be sure that restoring the old
                             * state of this PortAudioStream is the right
                             * thing to do in its new state.
                             */
                            if (stream == 0)
                            {
                                int deviceIndex = Pa.getDeviceIndex(deviceID);
                                // Checks if the previously used device is still
                                // available.
                                if(deviceIndex != Pa.paNoDevice)
                                {
                                    // If yes, then use it.
                                    setDeviceID(deviceID);
                                    if (start)
                                        start();
                                }
                            }
                        }
                        finally
                        {
                            /*
                             * If we had to attempt to restore the state of
                             * this PortAudioStream, we just did attempt to.
                             */
                            deviceID = null;
                            start = false;
                        }
                    }
                }

                public void willPaUpdateAvailableDeviceList()
                    throws Exception
                {
                    synchronized (PortAudioStream.this)
                    {
                        waitWhileStreamIsBusy();
                        if (stream == 0)
                        {
                            deviceID = null;
                            start = false;
                        }
                        else
                        {
                            deviceID = PortAudioStream.this.deviceID;
                            start = PortAudioStream.this.started;

                            boolean disconnected = false;

                            try
                            {
                                setDeviceID(null);
                                disconnected = true;
                            }
                            finally
                            {
                                /*
                                 * If we failed to disconnect this
                                 * PortAudioStream, we will not attempt to
                                 * restore its state later on.
                                 */
                                if (!disconnected)
                                {
                                    deviceID = null;
                                    start = false;
                                }
                            }
                        }
                    }
                }
            };

    /**
     * Current sequence number.
     */
    private int sequenceNumber = 0;

    private boolean started = false;

    /**
     * The input PortAudio stream represented by this instance.
     */
    private long stream = 0;

    /**
     * The indicator which determines whether {@link #stream} is busy and should
     * not, for example, be closed.
     */
    private boolean streamIsBusy = false;

    /**
     * Initializes a new <tt>PortAudioStream</tt> instance which is to have its
     * <tt>Format</tt>-related information abstracted by a specific
     * <tt>FormatControl</tt>.
     *
     * @param dataSource the <tt>DataSource</tt> which is creating the new
     * instance so that it becomes one of its <tt>streams</tt>
     * @param formatControl the <tt>FormatControl</tt> which is to abstract the
     * <tt>Format</tt>-related information of the new instance
     * @param audioQualityImprovement <tt>true</tt> to enable audio quality
     * improvement for the new instance in accord with the preferences of the
     * user or <tt>false</tt> to completely disable audio quality improvement
     */
    public PortAudioStream(
            DataSource dataSource,
            FormatControl formatControl,
            boolean audioQualityImprovement)
    {
        super(dataSource, formatControl);

        this.audioQualityImprovement = audioQualityImprovement;

        MediaServiceImpl mediaServiceImpl
            = NeomediaServiceUtils.getMediaServiceImpl();

        gainControl
            = (mediaServiceImpl == null)
                ? null
                : (GainControl) mediaServiceImpl.getInputVolumeControl();

        /*
         * XXX We will add a PaUpdateAvailableDeviceListListener and will not
         * remove it because we will rely on PortAudioSystem's use of
         * WeakReference.
         */
        PortAudioSystem.addPaUpdateAvailableDeviceListListener(
                paUpdateAvailableDeviceListListener);
    }

    private void connect()
        throws IOException
    {
        AudioFormat format = (AudioFormat) getFormat();
        int channels = format.getChannels();

        if (channels == Format.NOT_SPECIFIED)
            channels = 1;

        int deviceIndex = Pa.getDeviceIndex(this.deviceID);
        int sampleSizeInBits = format.getSampleSizeInBits();
        long sampleFormat = Pa.getPaSampleFormat(sampleSizeInBits);
        double sampleRate = format.getSampleRate();
        int framesPerBuffer
            = (int)
                ((sampleRate * Pa.DEFAULT_MILLIS_PER_BUFFER)
                    / (channels * 1000));

        try
        {
            inputParameters
                = Pa.StreamParameters_new(
                        deviceIndex,
                        channels,
                        sampleFormat,
                        Pa.getSuggestedLatency());

            stream
                = Pa.OpenStream(
                        inputParameters,
                        0 /* outputParameters */,
                        sampleRate,
                        framesPerBuffer,
                        Pa.STREAM_FLAGS_CLIP_OFF | Pa.STREAM_FLAGS_DITHER_OFF,
                        null /* streamCallback */);
        }
        catch (PortAudioException paex)
        {
            logger.error("Failed to open " + getClass().getSimpleName(), paex);

            IOException ioex = new IOException(paex.getLocalizedMessage());

            ioex.initCause(paex);
            throw ioex;
        }
        finally
        {
            if ((stream == 0) && (inputParameters != 0))
            {
                Pa.StreamParameters_free(inputParameters);
                inputParameters = 0;
            }
        }
        if (stream == 0)
            throw new IOException("Pa_OpenStream");

        this.framesPerBuffer = framesPerBuffer;
        bytesPerBuffer
            = Pa.GetSampleSize(sampleFormat) * channels * framesPerBuffer;

        /*
         * Know the Format in which this PortAudioStream will output audio
         * data so that it can report it without going through its
         * DataSource.
         */
        this.format
                = new AudioFormat(
                        AudioFormat.LINEAR,
                        sampleRate,
                        sampleSizeInBits,
                        channels,
                        AudioFormat.LITTLE_ENDIAN,
                        AudioFormat.SIGNED,
                        Format.NOT_SPECIFIED /* frameSizeInBits */,
                        Format.NOT_SPECIFIED /* frameRate */,
                        Format.byteArray);

        MediaServiceImpl mediaServiceImpl
            = NeomediaServiceUtils.getMediaServiceImpl();
        boolean denoise = DeviceConfiguration.DEFAULT_AUDIO_DENOISE;
        boolean echoCancel = DeviceConfiguration.DEFAULT_AUDIO_ECHOCANCEL;
        long echoCancelFilterLengthInMillis
            = DeviceConfiguration
                .DEFAULT_AUDIO_ECHOCANCEL_FILTER_LENGTH_IN_MILLIS;

        if (mediaServiceImpl != null)
        {
            DeviceConfiguration devCfg
                = mediaServiceImpl.getDeviceConfiguration();

            if (devCfg != null)
            {
                denoise = devCfg.isDenoise();
                echoCancel = devCfg.isEchoCancel();
                echoCancelFilterLengthInMillis
                    = devCfg.getEchoCancelFilterLengthInMillis();
            }
        }

        Pa.setDenoise(stream, audioQualityImprovement && denoise);
        Pa.setEchoFilterLengthInMillis(
                stream,
                (audioQualityImprovement && echoCancel)
                    ? echoCancelFilterLengthInMillis
                    : 0);
    }

    /**
     * Gets the <tt>Format</tt> of this <tt>PullBufferStream</tt> as directly
     * known by it.
     *
     * @return the <tt>Format</tt> of this <tt>PullBufferStream</tt> as directly
     * known by it or <tt>null</tt> if this <tt>PullBufferStream</tt> does not
     * directly know its <tt>Format</tt> and it relies on the
     * <tt>PullBufferDataSource</tt> which created it to report its
     * <tt>Format</tt>
     * @see AbstractPullBufferStream#doGetFormat()
     */
    @Override
    protected Format doGetFormat()
    {
        return (format == null) ? super.doGetFormat() : format;
    }

    /**
     * Reads media data from this <tt>PullBufferStream</tt> into a specific
     * <tt>Buffer</tt> with blocking.
     *
     * @param buffer the <tt>Buffer</tt> in which media data is to be read from
     * this <tt>PullBufferStream</tt>
     * @throws IOException if anything goes wrong while reading media data from
     * this <tt>PullBufferStream</tt> into the specified <tt>buffer</tt>
     */
    public void read(Buffer buffer)
        throws IOException
    {
        String message;

        synchronized (this)
        {
            if (stream == 0)
                message = "This " + getClass().getName() + " is disconnected.";
            else if (!started)
                message = "This " + getClass().getName() + " is stopped.";
            else
            {
                message = null;
                streamIsBusy = true;
            }
        }

        /*
         * The caller shouldn't call #read(Buffer) if this instance is
         * disconnected or stopped. Additionally, if she does, she may be
         * persistent. If we do not slow her down, she may hog the CPU.
         */
        if (message != null)
        {
            yield();
            throw new IOException(message);
        }

        long paErrorCode = Pa.paNoError;

        try
        {
            /*
             * Reuse the data of buffer in order to not perform unnecessary
             * allocations.
             */
            Object data = buffer.getData();
            byte[] bufferData = null;

            if (data instanceof byte[])
            {
                bufferData = (byte[]) data;
                if (bufferData.length < bytesPerBuffer)
                    bufferData = null;
            }
            if (bufferData == null)
            {
                bufferData = new byte[bytesPerBuffer];
                buffer.setData(bufferData);
            }

            try
            {
                Pa.ReadStream(stream, bufferData, framesPerBuffer);
            }
            catch (PortAudioException pae)
            {
                paErrorCode = pae.getErrorCode();

                logger.error("Failed to read from PortAudio stream.", pae);

                IOException ioe = new IOException(pae.getLocalizedMessage());

                ioe.initCause(pae);
                throw ioe;
            }

            // if we have some volume setting apply them
            if (gainControl != null)
            {
                AbstractVolumeControl.applyGain(
                        gainControl,
                        bufferData, 0, bytesPerBuffer);
            }

            long bufferTimeStamp = System.nanoTime();

            buffer.setFlags(Buffer.FLAG_SYSTEM_TIME);
            if (format != null)
                buffer.setFormat(format);
            buffer.setHeader(null);
            buffer.setLength(bytesPerBuffer);
            buffer.setOffset(0);
            buffer.setSequenceNumber(sequenceNumber++);
            buffer.setTimeStamp(bufferTimeStamp);
        }
        finally
        {
            synchronized (this)
            {
               streamIsBusy = false;
               notifyAll();
            }

            /*
             * If a timeout has occurred in the method Pa.ReadStream, give the
             * application a little time to allow it to possibly get its act
             * together.
             */
            if (Pa.paTimedOut == paErrorCode)
                yield();
        }
    }

    /**
     * Sets the device index of the PortAudio device to be read through this
     * <tt>PullBufferStream</tt>.
     *
     * @param deviceID The ID of the device used to be read trough this
     * PortAudioStream.  This String contains the deviceUID, or if not
     * available, the device name.  If set to null, then there was no device
     * used before the update.
     *
     * @throws IOException if input/output error occurred
     */
    synchronized void setDeviceID(String deviceID)
        throws IOException
    {
        if((this.deviceID == null && deviceID == null)
                || (this.deviceID != null && this.deviceID.equals(deviceID)))
            return;

        // DataSource#disconnect
        if (this.deviceID != null)
        {
            /*
             * Just to be on the safe side, make sure #read(Buffer) is not
             * currently executing.
             */
            waitWhileStreamIsBusy();

            if (stream != 0)
            {
                /*
                 * For the sake of completeness, attempt to stop this instance
                 * before disconnecting it.
                 */
                if (started)
                {
                    try
                    {
                        stop();
                    }
                    catch (IOException ioe)
                    {
                        /*
                         * The exception should have already been logged by the
                         * method #stop(). Additionally and as said above, we
                         * attempted it out of courtesy.
                         */
                    }
                }

                boolean closed = false;

                try
                {
                    Pa.CloseStream(stream);
                    closed = true;
                }
                catch (PortAudioException pae)
                {
                    /*
                     * The function Pa_CloseStream is not supposed to time out
                     * under normal execution. However, we have modified it to
                     * do so under exceptional circumstances on Windows at least
                     * in order to overcome endless loops related to
                     * hotplugging. In such a case, presume the native PortAudio
                     * stream closed in order to maybe avoid a crash at the risk
                     * of a memory leak.
                     */
                    if (pae.getErrorCode() == Pa.paTimedOut)
                        closed = true;

                    if (!closed)
                    {
                        logger.error(
                                "Failed to close " + getClass().getSimpleName(),
                                pae);

                        IOException ioe
                            = new IOException(pae.getLocalizedMessage());

                        ioe.initCause(pae);
                        throw ioe;
                    }
                }
                finally
                {
                    if (closed)
                    {
                        stream = 0;

                        if (inputParameters != 0)
                        {
                            Pa.StreamParameters_free(inputParameters);
                            inputParameters = 0;
                        }

                        /*
                         * Make sure this AbstractPullBufferStream asks its
                         * DataSource for the Format in which it is supposed to
                         * output audio data the next time it is opened instead
                         * of using its Format from a previous open.
                         */
                        this.format = null;
                    }
                }
            }
        }
        this.deviceID = deviceID;
        this.started = false;
        // DataSource#connect
        if (this.deviceID != null)
        {
            PortAudioSystem.willPaOpenStream();
            try
            {
                connect();
            }
            finally
            {
                PortAudioSystem.didPaOpenStream();
            }
        }
    }

    /**
     * Starts the transfer of media data from this <tt>PullBufferStream</tt>.
     *
     * @throws IOException if anything goes wrong while starting the transfer of
     * media data from this <tt>PullBufferStream</tt>
     */
    @Override
    public synchronized void start()
        throws IOException
    {
        if (stream != 0)
        {
            waitWhileStreamIsBusy();

            try
            {
                Pa.StartStream(stream);
                started = true;
            }
            catch (PortAudioException paex)
            {
                logger.error(
                        "Failed to start " + getClass().getSimpleName(),
                        paex);

                IOException ioex = new IOException(paex.getLocalizedMessage());

                ioex.initCause(paex);
                throw ioex;
            }
        }
    }

    /**
     * Stops the transfer of media data from this <tt>PullBufferStream</tt>.
     *
     * @throws IOException if anything goes wrong while stopping the transfer of
     * media data from this <tt>PullBufferStream</tt>
     */
    @Override
    public synchronized void stop()
        throws IOException
    {
        if (stream != 0)
        {
            waitWhileStreamIsBusy();

            try
            {
                Pa.StopStream(stream);
                started = false;
            }
            catch (PortAudioException paex)
            {
                logger.error(
                        "Failed to stop " + getClass().getSimpleName(),
                        paex);

                IOException ioex = new IOException(paex.getLocalizedMessage());

                ioex.initCause(paex);
                throw ioex;
            }
        }
    }

    /**
     * Waits on this instance while {@link #streamIsBusy} is equal to
     * <tt>true</tt> i.e. until it becomes <tt>false</tt>. The method should
     * only be called by a thread that is the owner of this object's monitor.
     */
    private void waitWhileStreamIsBusy()
    {
        boolean interrupted = false;

        while ((stream != 0) && streamIsBusy)
        {
            try
            {
                wait();
            }
            catch (InterruptedException iex)
            {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Causes the currently executing thread to temporarily pause and allow
     * other threads to execute.
     */
    public static void yield()
    {
        boolean interrupted = false;

        try
        {
            Thread.sleep(Pa.DEFAULT_MILLIS_PER_BUFFER);
        }
        catch (InterruptedException ie)
        {
            interrupted = true;
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
