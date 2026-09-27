package com.devopsaaas.integration.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.tool.container.LogLine;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DockerLogDecoderTest {

    @Test
    void aLineSplitAcrossFrames_isReassembled_perStream() {
        byte[] body = concat(
                frame(1, "2026-09-26T10:00:00Z hel"),
                frame(2, "2026-09-26T10:00:01Z oops\n"),
                frame(1, "lo\n"));

        DockerLogDecoder.Decoded decoded = DockerLogDecoder.decode(body, true, false, 100);

        assertThat(decoded.lines()).containsExactly(
                new LogLine(Instant.parse("2026-09-26T10:00:01Z"), "stderr", "oops"),
                new LogLine(Instant.parse("2026-09-26T10:00:00Z"), "stdout", "hello"));
        assertThat(decoded.truncated()).isFalse();
    }

    @Test
    void aPartialFrameAtTheEnd_isDropped_andReportedAsTruncation() {
        byte[] complete = frame(1, "2026-09-26T10:00:00Z one\n");
        byte[] partial = frame(1, "2026-09-26T10:00:01Z two\n");
        byte[] body = concat(complete, java.util.Arrays.copyOf(partial, 12));

        DockerLogDecoder.Decoded decoded = DockerLogDecoder.decode(body, true, false, 100);

        assertThat(decoded.lines()).extracting(LogLine::text).containsExactly("one");
        assertThat(decoded.truncated()).isTrue();
    }

    @Test
    void linesWithoutATimestamp_areKeptWhole() {
        DockerLogDecoder.Decoded decoded = DockerLogDecoder.decode(
                "not a timestamp here\r\n".getBytes(StandardCharsets.UTF_8), false, false, 100);

        assertThat(decoded.lines()).containsExactly(new LogLine(null, "stdout", "not a timestamp here"));
    }

    @Test
    void longLines_areCut_withoutSplittingASurrogatePair() {
        String text = "a".repeat(9) + "😀" + "tail";

        DockerLogDecoder.Decoded decoded = DockerLogDecoder.decode(
                (text + "\n").getBytes(StandardCharsets.UTF_8), false, false, 10);

        assertThat(decoded.lines().getFirst().text()).isEqualTo("a".repeat(9) + DockerLogDecoder.TRUNCATION_MARK);
        assertThat(decoded.truncated()).isTrue();
    }

    @Test
    void multiplexingIsDetected_fromTheMediaType_orFromTheFrameHeader() {
        byte[] framed = frame(1, "x\n");

        assertThat(DockerLogDecoder.isMultiplexed("application/vnd.docker.multiplexed-stream", new byte[0])).isTrue();
        assertThat(DockerLogDecoder.isMultiplexed("application/vnd.docker.raw-stream", framed)).isFalse();
        assertThat(DockerLogDecoder.isMultiplexed(null, framed)).isTrue();
        assertThat(DockerLogDecoder.isMultiplexed(null, "2026-09-26T10:00:00Z x\n".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    static byte[] frame(int stream, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + bytes.length).put((byte) stream).put(new byte[3]).putInt(bytes.length)
                .put(bytes).array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
