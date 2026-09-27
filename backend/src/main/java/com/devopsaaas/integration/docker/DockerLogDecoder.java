package com.devopsaaas.integration.docker;

import com.devopsaaas.tool.container.LogLine;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the body of {@code GET /containers/{id}/logs?timestamps=1} into log lines.
 *
 * <p>Without a TTY, Docker multiplexes stdout and stderr in frames: an 8-byte header (stream type, three zero
 * bytes, big-endian payload size) followed by the payload. With a TTY the body is the raw text. Frames do not
 * have to align with lines, so bytes are buffered per stream until a newline. A body cut by the size cap
 * ends in a partial frame, which is dropped and reported as truncation.
 */
final class DockerLogDecoder {

    static final String TRUNCATION_MARK = "…[line truncated]";
    private static final int HEADER_SIZE = 8;

    private DockerLogDecoder() {
    }

    record Decoded(List<LogLine> lines, boolean truncated) {
    }

    /** Media type Docker sends for multiplexed logs (API 1.42 and later); older engines send none. */
    static boolean isMultiplexed(String contentType, byte[] body) {
        if (contentType != null) {
            if (contentType.startsWith("application/vnd.docker.multiplexed-stream")) {
                return true;
            }
            if (contentType.startsWith("application/vnd.docker.raw-stream")) {
                return false;
            }
        }
        return body.length >= HEADER_SIZE && body[0] >= 0 && body[0] <= 2 && body[1] == 0 && body[2] == 0
                && body[3] == 0;
    }

    static Decoded decode(byte[] body, boolean multiplexed, boolean bodyCut, int maxLineLength) {
        Lines lines = new Lines(maxLineLength);
        boolean truncated = bodyCut;
        if (multiplexed) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.wrap(body);
            while (buffer.remaining() >= HEADER_SIZE) {
                int stream = buffer.get();
                buffer.position(buffer.position() + 3);
                int size = buffer.getInt();
                if (size < 0 || size > buffer.remaining()) {
                    truncated = true;
                    break;
                }
                byte[] payload = new byte[size];
                buffer.get(payload);
                if (stream == 2) {
                    lines.feed(stderr, payload, "stderr");
                } else {
                    lines.feed(stdout, payload, "stdout");
                }
            }
            if (buffer.remaining() > 0 && buffer.remaining() < HEADER_SIZE) {
                truncated = true;
            }
            lines.flush(stdout, "stdout");
            lines.flush(stderr, "stderr");
        } else {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            lines.feed(raw, body, "stdout");
            lines.flush(raw, "stdout");
        }
        return new Decoded(List.copyOf(lines.result), truncated || lines.cut);
    }

    private static final class Lines {

        private final int maxLineLength;
        private final List<LogLine> result = new ArrayList<>();
        private boolean cut;

        Lines(int maxLineLength) {
            this.maxLineLength = maxLineLength;
        }

        void feed(ByteArrayOutputStream pending, byte[] bytes, String stream) {
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    pending.write(bytes, start, i - start);
                    emit(pending, stream);
                    start = i + 1;
                }
            }
            pending.write(bytes, start, bytes.length - start);
        }

        void flush(ByteArrayOutputStream pending, String stream) {
            if (pending.size() > 0) {
                emit(pending, stream);
            }
        }

        private void emit(ByteArrayOutputStream pending, String stream) {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            Instant timestamp = null;
            String text = line;
            int space = line.indexOf(' ');
            if (space > 0) {
                try {
                    timestamp = Instant.parse(line.substring(0, space));
                    text = line.substring(space + 1);
                } catch (DateTimeParseException notATimestamp) {
                    // A line without the timestamp prefix is kept whole.
                }
            }
            result.add(new LogLine(timestamp, stream, limit(text)));
        }

        private String limit(String text) {
            if (text.length() <= maxLineLength) {
                return text;
            }
            cut = true;
            int end = Character.isHighSurrogate(text.charAt(maxLineLength - 1)) ? maxLineLength - 1 : maxLineLength;
            return text.substring(0, end) + TRUNCATION_MARK;
        }
    }
}
