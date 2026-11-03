package client;

import java.io.IOException;
import java.net.DatagramSocket;

/** Reserve actual media ports before advertising them. Keep sockets until the call ends. */
public final class CallSession implements AutoCloseable {
    public final String id;
    public final DatagramSocket video;
    public final DatagramSocket audio;
    public final boolean videoEnabled;

    public CallSession(String id, boolean videoEnabled) throws IOException {
        this.id = id;
        this.videoEnabled = videoEnabled;
        video = new DatagramSocket(0);
        try { audio = new DatagramSocket(0); }
        catch (IOException ex) { video.close(); throw ex; }
    }
    public boolean isClosed() { return video.isClosed() || audio.isClosed(); }
    @Override public void close() { video.close(); audio.close(); }
}
