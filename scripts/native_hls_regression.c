/* Owned, in-memory inputs for the embedded FFmpeg HLS demuxer.
 * Link against the same static archives used for the candidate libvlc.so.
 * No network, provider data, or filesystem reads are performed by the callbacks.
 * The callback cap also bounds execution when testing an unpatched archive.
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavutil/mem.h>

typedef struct { const char *text; size_t offset; } Input;
static const char recursive[] =
    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n"
    "http://owned.invalid/recursive.m3u8\n";
static int opens;

static int read_input(void *opaque, uint8_t *buffer, int capacity) {
    Input *input = opaque;
    size_t remaining = strlen(input->text) - input->offset;
    if (!remaining) return AVERROR_EOF;
    size_t size = remaining < (size_t)capacity ? remaining : (size_t)capacity;
    memcpy(buffer, input->text + input->offset, size);
    input->offset += size;
    return (int)size;
}

static AVIOContext *input(const char *text) {
    Input *state = av_mallocz(sizeof(*state));
    unsigned char *buffer = av_malloc(4096);
    if (!state || !buffer) { av_free(state); av_free(buffer); return NULL; }
    state->text = text;
    AVIOContext *io = avio_alloc_context(buffer, 4096, 0, state, read_input, NULL, NULL);
    if (!io) { av_free(state); av_free(buffer); }
    return io;
}

static int close_input(AVFormatContext *context, AVIOContext *io) {
    (void)context;
    av_freep(&io->opaque);
    av_freep(&io->buffer);
    avio_context_free(&io);
    return 0;
}

static int open_child(AVFormatContext *context, AVIOContext **io,
                      const char *url, int flags, AVDictionary **options) {
    (void)context; (void)url; (void)flags; (void)options;
    if (++opens > 8) return AVERROR_EXIT;
    *io = input(recursive);
    return *io ? 0 : AVERROR(ENOMEM);
}

static int probe(const char *name, const char *playlist, int expected_opens) {
    AVFormatContext *context = avformat_alloc_context();
    AVIOContext *main_input = input(playlist);
    if (!context || !main_input) return 1;
    context->pb = main_input;
    context->flags |= AVFMT_FLAG_CUSTOM_IO;
    context->io_open = open_child;
    context->io_close2 = close_input;
    AVDictionary *options = NULL;
    av_dict_set(&options, "http_persistent", "0", 0);
    opens = 0;
    int result = avformat_open_input(&context, "http://owned.invalid/master.m3u8",
                                    av_find_input_format("hls"), &options);
    int pass = result < 0 && opens == expected_opens;
    printf("%s: %s (result=%d child_opens=%d)\n", name, pass ? "PASS" : "FAIL", result, opens);
    av_dict_free(&options);
    avformat_close_input(&context);
    close_input(NULL, main_input);
    return !pass;
}

int main(void) {
    av_log_set_level(AV_LOG_QUIET);
    int failed = probe("recursive-child-master-rejected", recursive, 1);
    failed += probe("child-extension-checked-before-io",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n"
                    "file:///owned-fixture.txt\n", 0);
    failed += probe("child-protocol-checked-before-io",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n"
                    "concat:owned-fixture.ts\n", 0);
    return failed ? 1 : 0;
}
