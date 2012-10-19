/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.notify;

import java.beans.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import javax.media.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.audionotifier.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.resources.*;

/**
 * The implementation of <tt>AudioNotifierService</tt>.
 *
 * @author Yana Stamcheva
 * @author Lyubomir Marinov
 */
public class AudioNotifierServiceImpl
    implements AudioNotifierService,
               PropertyChangeListener
{
    /**
     * The cache of <tt>SCAudioClip</tt> instances which we may reuse. The reuse
     * is complex because a <tt>SCAudioClip</tt> may be used by a single user at
     * a time. 
     */
    private Map<AudioKey, SCAudioClip> audios;

    /**
     * The <tt>Object</tt> which synchronizes the access to {@link #audios}.
     */
    private final Object audiosSyncRoot = new Object();

    /**
     * The <tt>DeviceConfiguration</tt> which provides information about the
     * notify and playback devices on which this instance plays
     * <tt>SCAudioClip</tt>s.
     */
    private final DeviceConfiguration deviceConfiguration;

    /**
     * The indicator which determined whether <tt>SCAudioClip</tt>s are to be
     * played by this instance.
     */
    private boolean mute;

    /**
     * Initializes a new <tt>AudioNotifierServiceImpl</tt> instance.
     */
    public AudioNotifierServiceImpl()
    {
        this.deviceConfiguration
            = NeomediaServiceUtils
                .getMediaServiceImpl()
                    .getDeviceConfiguration();

        this.deviceConfiguration.addPropertyChangeListener(this);
    }

    /**
     * Checks whether the playback and notification configuration
     * share the same device.
     * @return are audio out and notifications using the same device.
     */
    public boolean audioOutAndNotificationsShareSameDevice()
    {
        AudioSystem audioSystem = getDeviceConfiguration().getAudioSystem();
        CaptureDeviceInfo notify
            = audioSystem.getDevice(AudioSystem.NOTIFY_INDEX);
        CaptureDeviceInfo playback
            = audioSystem.getDevice(AudioSystem.PLAYBACK_INDEX);

        return
            (notify == null)
                ? (playback == null)
                : notify.getLocator().equals(playback.getLocator());
    }

    /**
     * Creates an SCAudioClip from the given URI and adds it to the list of
     * available audio-s. Uses notification device if any.
     *
     * @param uri the path where the audio file could be found
     * @return a newly created <tt>SCAudioClip</tt> from <tt>uri</tt>
     */
    public SCAudioClip createAudio(String uri)
    {
        return createAudio(uri, false);
    }

    /**
     * Creates an SCAudioClip from the given URI and adds it to the list of
     * available audio-s.
     *
     * @param uri the path where the audio file could be found
     * @param playback use or not the playback device.
     * @return a newly created <tt>SCAudioClip</tt> from <tt>uri</tt>
     */
    public SCAudioClip createAudio(String uri, boolean playback)
    {
        SCAudioClip audio;

        synchronized (audiosSyncRoot)
        {
            final AudioKey key = new AudioKey(uri, playback);

            /*
             * While we want to reuse the SCAudioClip instances, they may be
             * used by a single user at a time. That's why we'll forget about
             * them while they are in use and we'll reclaim them when they are
             * no longer in use.
             */
            audio = (audios == null) ? null : audios.remove(key);

            if (audio == null)
            {
                ResourceManagementService resources
                    = LibJitsi.getResourceManagementService();
                URL url
                    = (resources == null)
                        ? null
                        : resources.getSoundURLForPath(uri);

                if (url == null)
                {
                    // Not found by the class loader. Perhaps it's a local file.
                    try
                    {
                        url = new URL(uri);
                    }
                    catch (MalformedURLException e)
                    {
                        return null;
                    }
                }

                try
                {
                    AudioSystem audioSystem
                        = getDeviceConfiguration().getAudioSystem();

                    if (audioSystem == null)
                    {
                        audio = new JavaSoundClipImpl(url, this);
                    }
                    else if (NoneAudioSystem.LOCATOR_PROTOCOL.equalsIgnoreCase(
                            audioSystem.getLocatorProtocol()))
                    {
                        audio = null;
                    }
                    else
                    {
                        audio
                            = new AudioSystemClipImpl(
                                    url,
                                    this,
                                    audioSystem,
                                    playback);
                    }
                }
                catch (Throwable t)
                {
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                    else
                    {
                        /*
                         * Could not initialize a new SCAudioClip instance to be
                         * played.
                         */
                        return null;
                    }
                }
            }

            /*
             * Make sure that the SCAudioClip will be reclaimed for reuse when
             * it is no longer in use.
             */
            if (audio != null)
            {
                if (audios == null)
                    audios = new HashMap<AudioKey, SCAudioClip>();

                /*
                 * We have to return in the Map which was active at the time the
                 * SCAudioClip was initialized because it may have become
                 * invalid if the playback or notify audio device changed.
                 */
                final Map<AudioKey, SCAudioClip> finalAudios = audios;
                final SCAudioClip finalAudio = audio;

                audio
                    = new SCAudioClip()
                    {
                        @Override
                        protected void finalize()
                            throws Throwable
                        {
                            try
                            {
                                synchronized (audios)
                                {
                                    finalAudios.put(key, finalAudio);
                                }
                            }
                            finally
                            {
                                super.finalize();
                            }
                        }

                        public void play()
                        {
                            finalAudio.play();
                        }

                        public void play(
                                int loopInterval,
                                Callable<Boolean> loopCondition)
                        {
                            finalAudio.play(loopInterval, loopCondition);
                        }

                        public void stop()
                        {
                            finalAudio.stop();
                        }
                    };
            }
        }

        return audio;
    }

    /**
     * The device configuration.
     *
     * @return the deviceConfiguration
     */
    public DeviceConfiguration getDeviceConfiguration()
    {
        return deviceConfiguration;
    }

    /**
     * Returns TRUE if the sound is currently disabled, FALSE otherwise.
     * @return TRUE if the sound is currently disabled, FALSE otherwise
     */
    public boolean isMute()
    {
        return mute;
    }

    /**
     * Listens for changes in notify device
     * @param ev the event that notify device has changed.
     */
    public void propertyChange(PropertyChangeEvent ev)
    {
        String propertyName = ev.getPropertyName();

        if (DeviceConfiguration.AUDIO_NOTIFY_DEVICE.equals(propertyName)
                || DeviceConfiguration.AUDIO_PLAYBACK_DEVICE.equals(
                        propertyName))
        {
            synchronized (audiosSyncRoot)
            {
                /*
                 * Make sure that the currently referenced SCAudioClips will not
                 * be reclaimed.
                 */
                audios = null;
            }
        }
    }

    /**
     * Enables or disables the sound in the application. If FALSE, we try to
     * restore all looping sounds if any.
     *
     * @param mute when TRUE disables the sound, otherwise enables the sound.
     */
    public void setMute(boolean mute)
    {
        this.mute = mute;

        // TODO Auto-generated method stub
    }

    /**
     * Implements the key of {@link AudioNotifierServiceImpl#audios}. Combines the
     * <tt>uri</tt> of the <tt>SCAudioClip</tt> with the indicator which
     * determines whether the <tt>SCAudioClip</tt> in question uses the playback
     * or the notify audio device.
     */
    private static class AudioKey
    {
        /**
         * Is it playback?
         */
        private final boolean playback;

        /**
         * The uri.
         */
        final String uri;

        /**
         * Initializes a new <tt>AudioKey</tt> instance.
         *
         * @param uri
         * @param playback
         */
        private AudioKey(String uri, boolean playback)
        {
            this.uri = uri;
            this.playback = playback;
        }

        @Override
        public boolean equals(Object o)
        {
            if (o == this)
                return true;
            if (o == null)
                return false;

            AudioKey that = (AudioKey) o;

            return
                (playback == that.playback)
                    && ((uri == null)
                            ? (that.uri == null)
                            : uri.equals(that.uri));
        }

        @Override
        public int hashCode()
        {
            return ((uri == null) ? 0 : uri.hashCode()) + (playback ? 1 : 0);
        }
    }
}
