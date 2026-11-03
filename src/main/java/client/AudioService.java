package client;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Microphone capture, bounded WAV notes and bidirectional PCM calls. */
public final class AudioService implements AutoCloseable {
    public static final AudioFormat FORMAT = new AudioFormat(16000.0f, 16, 1, true, false);
    private static final int MAX_PCM = Peer.MAX_VOICE_BYTES - 44;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Recording recording;
    private Clip currentClip;
    private DatagramSocket callUdpSocket;
    private TargetDataLine callMic;
    private SourceDataLine callSpeaker;
    private final AtomicBoolean muted = new AtomicBoolean();
    private volatile boolean closed;
    private Consumer<String> errors = text -> System.err.println(text);
    private static final class Recording {
        final TargetDataLine line;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        volatile boolean running = true;
        volatile boolean limitReached;
        Future<?> task;
        Recording(TargetDataLine line) { this.line = line; }
    }
    public void onError(Consumer<String> callback) { errors = callback; }
    public boolean isRecording() { return recording != null; }
    public boolean recordingLimitReached() { return recording != null && recording.limitReached; }
    public synchronized void startRecording(Consumer<Integer> level) throws IOException {
        if (closed) throw new IOException("Dịch vụ âm thanh đã đóng");
        if (recording != null) return;
        TargetDataLine line = null;
        try {
            line = AudioSystem.getTargetDataLine(FORMAT); line.open(FORMAT); line.start();
            var state = new Recording(line); recording = state;
            state.task = executor.submit(() -> {
                byte[] bytes = new byte[2048];
                try {
                    while (state.running) {
                        int read = state.line.read(bytes, 0, bytes.length);
                        if (read <= 0) continue;
                        synchronized (state.buffer) {
                            int remaining = MAX_PCM - state.buffer.size();
                            state.buffer.write(bytes, 0, Math.min(read, remaining));
                            if (state.buffer.size() == MAX_PCM) { state.limitReached = true; state.running = false; }
                        }
                        if (level != null) level.accept(calculateLevel(bytes, read));
                    }
                } catch (RuntimeException ex) { if (state.running) errors.accept("Ghi âm: " + ex.getMessage()); }
                finally { state.line.stop(); state.line.close(); }
            });
        } catch (LineUnavailableException | IllegalArgumentException ex) {
            if (line != null) line.close();
            throw new IOException("Không mở được micro: " + ex.getMessage(), ex);
        }
    }
    public void startRecording() throws IOException { startRecording(null); }
    public byte[] stopRecording() throws IOException {
        Recording state;
        synchronized (this) { state = recording; recording = null; }
        if (state == null) return new byte[0];
        state.running = false; state.line.stop(); state.line.close();
        try { state.task.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ex) { state.task.cancel(true); }
        synchronized (state.buffer) { return pcmToWav(state.buffer.toByteArray(), FORMAT); }
    }
    public synchronized void playAudio(byte[] wavData, Runnable onComplete) throws IOException {
        stopPlaying();
        if (wavData == null || wavData.length == 0) return;
        try (var ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavData))) {
            var clip = AudioSystem.getClip(); currentClip = clip; clip.open(ais);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && onComplete != null) onComplete.run();
            });
            clip.start();
        } catch (UnsupportedAudioFileException | LineUnavailableException | IllegalArgumentException ex) {
            stopPlaying(); throw new IOException("Không thể phát âm thanh: " + ex.getMessage(), ex);
        }
    }
    public synchronized void stopPlaying() {
        if (currentClip != null) { currentClip.stop(); currentClip.close(); currentClip = null; }
    }
    public synchronized void setMuted(boolean value) { muted.set(value); }
    public boolean isMuted() { return muted.get(); }
    public synchronized int startVoiceCall(InetAddress target, int port, int localPort, Consumer<Integer> level) throws IOException {
        return startVoiceCall(target, port, new DatagramSocket(localPort), level);
    }
    public int startVoiceCall(InetAddress target, int port, int localPort) throws IOException {
        return startVoiceCall(target, port, localPort, null);
    }
    public synchronized int startVoiceCall(InetAddress target, int port, DatagramSocket socket, Consumer<Integer> level) throws IOException {
        stopVoiceCall();
        if (closed || socket.isClosed()) { socket.close(); throw new IOException("Cuộc gọi đã đóng"); }
        TargetDataLine mic = null; SourceDataLine speaker = null;
        try {
            mic = AudioSystem.getTargetDataLine(FORMAT); speaker = AudioSystem.getSourceDataLine(FORMAT);
            mic.open(FORMAT); speaker.open(FORMAT); mic.start(); speaker.start();
        } catch (LineUnavailableException | IllegalArgumentException ex) {
            if (mic != null) mic.close(); if (speaker != null) speaker.close(); socket.close();
            throw new IOException("Không mở được micro/loa: " + ex.getMessage(), ex);
        }
        callUdpSocket = socket; callMic = mic; callSpeaker = speaker; muted.set(false);
        final TargetDataLine source = mic; final SourceDataLine sink = speaker;
        executor.submit(() -> {
            byte[] bytes = new byte[640];
            try {
                while (!socket.isClosed()) {
                    int read = source.read(bytes, 0, bytes.length);
                    if (read > 0) {
                        int volume = muted.get() ? 0 : calculateLevel(bytes, read);
                        if (muted.get()) Arrays.fill(bytes, 0, read, (byte)0);
                        socket.send(new DatagramPacket(bytes, read, target, port));
                        if (level != null) level.accept(volume);
                    }
                }
            } catch (Exception ex) { if (!socket.isClosed()) errors.accept("Gửi âm thanh: " + ex.getMessage()); }
            finally { source.stop(); source.close(); }
        });
        executor.submit(() -> {
            byte[] bytes = new byte[2048];
            try {
                while (!socket.isClosed()) {
                    var packet = new DatagramPacket(bytes, bytes.length); socket.receive(packet);
                    if (!target.equals(packet.getAddress()) || packet.getPort() != port) continue;
                    if (packet.getLength() % 2 == 0) sink.write(bytes, 0, packet.getLength());
                }
            } catch (Exception ex) { if (!socket.isClosed()) errors.accept("Nhận âm thanh: " + ex.getMessage()); }
            finally { sink.stop(); sink.close(); }
        });
        return socket.getLocalPort();
    }
    public synchronized void stopVoiceCall() {
        if (callUdpSocket != null) { callUdpSocket.close(); callUdpSocket = null; }
        if (callMic != null) { callMic.stop(); callMic.close(); callMic = null; }
        if (callSpeaker != null) { callSpeaker.stop(); callSpeaker.close(); callSpeaker = null; }
    }
    @Override public void close() {
        closed = true;
        try { stopRecording(); } catch (IOException ex) { errors.accept(ex.getMessage()); }
        stopPlaying(); stopVoiceCall(); executor.shutdownNow();
    }

    /** Tính toán mức độ lớn âm thanh RMS (0 - 100%) từ tín hiệu mẫu PCM 16-bit. */
    private static int calculateLevel(byte[] buffer, int read) {
        long sum = 0;
        int count = 0;
        for (int i = 0; i < read - 1; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += (long) sample * sample;
            count++;
        }
        if (count == 0) return 0;
        double rms = Math.sqrt(sum / (double) count);
        return Math.min(100, (int) (rms / 250.0));
    }

    /** Đóng gói dữ liệu thô PCM thành file WAV chuẩn có 44-byte Header. */
    public static byte[] pcmToWav(byte[] pcmData, AudioFormat format) throws IOException {
        long totalAudioLen = pcmData.length;
        long totalDataLen = totalAudioLen + 36;
        long longSampleRate = (long) format.getSampleRate();
        int channels = format.getChannels();
        long byteRate = (long) (format.getSampleRate() * channels * (format.getSampleSizeInBits() / 8));

        var out = new ByteArrayOutputStream();
        writeWavHeader(out, totalAudioLen, totalDataLen, longSampleRate, channels, byteRate, format.getSampleSizeInBits());
        out.write(pcmData);
        return out.toByteArray();
    }

    private static void writeWavHeader(OutputStream out, long totalAudioLen, long totalDataLen,
                                       long sampleRate, int channels, long byteRate, int bitsPerSample) throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // Kích thước fmt chunk = 16
        header[20] = 1; header[21] = 0; // PCM format = 1
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (bitsPerSample / 8)); // Block align
        header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
    }
}
