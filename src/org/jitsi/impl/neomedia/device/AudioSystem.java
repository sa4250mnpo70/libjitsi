/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.device;

import java.io.*;
import java.net.*;
import java.util.*;

import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.resources.*;
import org.jitsi.util.*;

import javax.media.*;
import javax.sound.sampled.*;

/**
 * Represents a <tt>DeviceSystem</tt> which provides support for the devices to
 * capture and play back audio (media). Examples include implementations which
 * integrate the native PortAudio, PulseAudio libraries.
 *
 * @author Lyubomir Marinov
 * @author Vincent Lucas
 */
public abstract class AudioSystem
    extends DeviceSystem
{
    /**
     * The index of the capture devices.
     */
    public static final int CAPTURE_INDEX = 0;

    /**
     * The constant/flag (to be) returned by {@link #getFeatures()} in order to
     * indicate that the respective <tt>AudioSystem</tt> supports toggling its
     * denoise functionality between on and off. The UI will look for the
     * presence of the flag in order to determine whether a check box is to be
     * shown to the user to enable toggling the denoise functionality.
     */
    public static final int FEATURE_DENOISE = 2;

    /**
     * The constant/flag (to be) returned by {@link #getFeatures()} in order to
     * indicate that the respective <tt>AudioSystem</tt> supports toggling its
     * echo cancellation functionality between on and off. The UI will look for
     * the presence of the flag in order to determine whether a check box is to
     * be shown to the user to enable toggling the echo cancellation
     * functionality.
     */
    public static final int FEATURE_ECHO_CANCELLATION = 4;

    /**
     * The constant/flag (to be) returned by {@link #getFeatures()} in order to
     * indicate that the respective <tt>AudioSystem</tt> differentiates between
     * playback and notification audio devices. The UI, for example, will look
     * for the presence of the flag in order to determine whether separate combo
     * boxes are to be shown to the user to allow the configuration of the
     * preferred playback and notification audio devices.
     */
    public static final int FEATURE_NOTIFY_AND_PLAYBACK_DEVICES = 8;
    
    public static final String LOCATOR_PROTOCOL_AUDIORECORD = "audiorecord";

    public static final String LOCATOR_PROTOCOL_JAVASOUND = "javasound";

    public static final String LOCATOR_PROTOCOL_OPENSLES = "opensles";

    public static final String LOCATOR_PROTOCOL_PORTAUDIO = "portaudio";

    public static final String LOCATOR_PROTOCOL_PULSEAUDIO = "pulseaudio";

    /**
     * The <tt>Logger</tt> used by this instance for logging output.
     */
    private static Logger logger = Logger.getLogger(AudioSystem.class);

    /**
     * The index of the notify devices.
     */
    public static final int NOTIFY_INDEX = 1;

    /**
     * The index of the playback devices.
     */
    public static final int PLAYBACK_INDEX = 2;

    public static AudioSystem getAudioSystem(String locatorProtocol)
    {
        AudioSystem[] audioSystems = getAudioSystems();
        AudioSystem audioSystemWithLocatorProtocol = null;

        if (audioSystems != null)
        {
            for (AudioSystem audioSystem : audioSystems)
            {
                if (audioSystem.getLocatorProtocol().equalsIgnoreCase(
                        locatorProtocol))
                {
                    audioSystemWithLocatorProtocol = audioSystem;
                    break;
                }
            }
        }
        return audioSystemWithLocatorProtocol;
    }

    public static AudioSystem[] getAudioSystems()
    {
        DeviceSystem[] deviceSystems
            = DeviceSystem.getDeviceSystems(MediaType.AUDIO);
        List<AudioSystem> audioSystems;

        if (deviceSystems == null)
            audioSystems = null;
        else
        {
            audioSystems = new ArrayList<AudioSystem>(deviceSystems.length);
            for (DeviceSystem deviceSystem : deviceSystems)
                if (deviceSystem instanceof AudioSystem)
                    audioSystems.add((AudioSystem) deviceSystem);
        }
        return
            (audioSystems == null)
                ? null
                : audioSystems.toArray(new AudioSystem[audioSystems.size()]);
    }

    /**
     * The list of devices detected by this <tt>AudioSystem</tt> indexed by
     * their category which is among {@link #CAPTURE_INDEX},
     * {@link #NOTIFY_INDEX} and {@link #PLAYBACK_INDEX}.
     */
    private Devices[] devices;

    protected AudioSystem(String locatorProtocol)
        throws Exception
    {
        this(locatorProtocol, 0);
    }

    protected AudioSystem(String locatorProtocol, int features)
        throws Exception
    {
        super(MediaType.AUDIO, locatorProtocol, features);
    }

    /**
     * Obtains an audio input stream from the URL provided.
     * @param uri a valid uri to a sound resource.
     * @return the input stream to audio data.
     * @throws IOException if an I/O exception occurs
     */
    public InputStream getAudioInputStream(String uri)
        throws IOException
    {
        ResourceManagementService resources
            = LibJitsi.getResourceManagementService();
        URL url
            = (resources == null)
                ? null
                : resources.getSoundURLForPath(uri);
        AudioInputStream audioStream = null;

        try
        {
            // Not found by the class loader? Perhaps it is a local file.
            if (url == null)
                url = new URL(uri);

            audioStream
                = javax.sound.sampled.AudioSystem.getAudioInputStream(url);
        }
        catch (MalformedURLException murle)
        {
            // Do nothing, the value of audioStream will remain equal to null.
        }
        catch (UnsupportedAudioFileException uafe)
        {
            logger.error("Unsupported format of audio stream " + url, uafe);
        }

        return audioStream;
    }

    /**
     * Gets the selected device for a specific kind: capture, notify or
     * playback.
     *
     * @param index The index of the specific devices: capture, notify or
     * playback.
     * @return The selected device for a specific kind: capture, notify or
     * playback.
     */
    public ExtendedCaptureDeviceInfo getDevice(int index)
    {
        return
            devices[index].getDevice(getLocatorProtocol(), getDevices(index));
    }

    /**
     * Gets the list of a kind of devices: capture, notify or playback.
     *
     * @param index The index of the specific devices: capture, notify or
     * playback.
     *
     * @return The list of a kind of devices: capture, notify or playback.
     */
    public List<ExtendedCaptureDeviceInfo> getDevices(int index)
    {
        return devices[index].getDevices();
    }

    /**
     * Returns the FMJ format of a specific <tt>InputStream</tt> providing audio
     * media.
     *
     * @param audioInputStream the <tt>InputStream</tt> providing audio media to
     * determine the FMJ format of
     * @return the FMJ format of the specified <tt>audioInputStream</tt> or
     * <tt>null</tt> if such an FMJ format could not be determined
     */
    public Format getFormat(InputStream audioInputStream)
    {
        if ((audioInputStream instanceof AudioInputStream))
        {
            AudioFormat audioInputStreamFormat
                = ((AudioInputStream) audioInputStream).getFormat();

            return
                new javax.media.format.AudioFormat(
                        javax.media.format.AudioFormat.LINEAR,
                        audioInputStreamFormat.getSampleRate(),
                        audioInputStreamFormat.getSampleSizeInBits(),
                        audioInputStreamFormat.getChannels());
        }

        return null;
    }

    /**
     * {@inheritDoc}
     *
     * Because <tt>AudioSystem</tt> may support playback and notification audio
     * devices apart from capture audio devices, fires more specific
     * <tt>PropertyChangeEvent</tt>s than <tt>DeviceSystem</tt>
     */
    @Override
    protected void postInitialize()
    {
        try
        {
            try
            {
                postInitializeSpecificDevices(CAPTURE_INDEX);
            }
            finally
            {
                if ((FEATURE_NOTIFY_AND_PLAYBACK_DEVICES & getFeatures()) != 0)
                {
                    try
                    {
                        postInitializeSpecificDevices(NOTIFY_INDEX);
                    }
                    finally
                    {
                        postInitializeSpecificDevices(PLAYBACK_INDEX);
                    }
                }
            }
        }
        finally
        {
            super.postInitialize();
        }
    }

    /**
     * Sets the device lists after the different audio systems (PortAudio,
     * PulseAudio, etc) have finished detecting their devices.
     *
     * @param index The index corresponding to a specific device kind:
     * capture/notify/playback.
     */
    protected void postInitializeSpecificDevices(int index)
    {
        // Gets all current active devices.
        List<ExtendedCaptureDeviceInfo> activeDevices = getDevices(index);
        // Gets the default device.
        Devices devices = this.devices[index];
        String locatorProtocol = getLocatorProtocol();
        ExtendedCaptureDeviceInfo selectedActiveDevice
            = devices.getDevice(locatorProtocol, activeDevices);

        // Sets the default device as selected. The function will fire a
        // property change only if the device has changed from a previous
        // configuration. The "set" part is important because only the fired
        // property event provides a way to get the hotplugged devices working
        // during a call.
        devices.setDevice(
                locatorProtocol,
                selectedActiveDevice,
                false);
    }

    /**
     * {@inheritDoc}
     *
     * Removes any capture, playback and notification devices previously
     * detected by this <tt>AudioSystem</tt> and prepares it for the execution
     * of its {@link DeviceSystem#doInitialize()} implementation (which detects
     * all devices to be provided by this instance).
     */
    @Override
    protected void preInitialize()
    {
        super.preInitialize();

        if (devices == null)
        {
            devices = new Devices[3];
            devices[CAPTURE_INDEX] = new CaptureDevices(this);
            devices[NOTIFY_INDEX] = new NotifyDevices(this);
            devices[PLAYBACK_INDEX] = new PlaybackDevices(this);
        }
    }

    /**
     * Fires a new <tt>PropertyChangeEvent</tt> to the
     * <tt>PropertyChangeListener</tt>s registered with this
     * <tt>PropertyChangeNotifier</tt> in order to notify about a change in the
     * value of a specific property which had its old value modified to a
     * specific new value. <tt>PropertyChangeNotifier</tt> does not check
     * whether the specified <tt>oldValue</tt> and <tt>newValue</tt> are indeed
     * different.
     * 
     * @param property the name of the property of this
     * <tt>PropertyChangeNotifier</tt> which had its value changed
     * @param oldValue the value of the property with the specified name before
     * the change
     * @param newValue the value of the property with the specified name after
     * the change
     */
    public void propertyChange(
            String property,
            Object oldValue,
            Object newValue)
    {
        firePropertyChange(property, oldValue, newValue);
    }

    /**
     * Sets the list of a kind of devices: capture, notify or playback.
     *
     * @param activeCaptureDevices The list of a kind of devices: capture,
     * notify or playback.
     */
    protected void setCaptureDevices(
            List<ExtendedCaptureDeviceInfo> activeCaptureDevices)
    {
        devices[CAPTURE_INDEX].setActiveDevices(activeCaptureDevices);
    }

    /**
     * Selects the active device.
     *
     * @param index The index corresponding to a specific device kind:
     * capture/notify/playback.
     * @param device The selected active device.
     * @param save Flag set to true in order to save this choice in the
     * configuration. False otherwise.
     */
    public void setDevice(
            int index,
            ExtendedCaptureDeviceInfo device,
            boolean save)
    {
        devices[index].setDevice(
                getLocatorProtocol(),
                device,
                save);
    }

    /**
     * Sets the list of the active devices.
     *
     * @param activePlaybackDevices The list of the active devices.
     */
    protected void setPlaybackDevices(
            List<ExtendedCaptureDeviceInfo> activePlaybackDevices)
    {
        devices[PLAYBACK_INDEX].setActiveDevices(activePlaybackDevices);
        // The notify devices are the same as the playback devices.
        devices[NOTIFY_INDEX].setActiveDevices(activePlaybackDevices);
    }
}
