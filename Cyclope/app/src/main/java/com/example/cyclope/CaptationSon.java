package com.example.cyclope;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;

public class CaptationSon {

    private final AudioSource audioSource;
    private final AudioTrack audioTrack;

    public CaptationSon(PeerConnectionFactory factory) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioSource = factory.createAudioSource(constraints);
        audioTrack = factory.createAudioTrack("a0", audioSource);
        audioTrack.setEnabled(true);
    }

    public AudioTrack getTrack() { return audioTrack; }

    public void dispose() {
        audioTrack.dispose();
        audioSource.dispose();
    }
}