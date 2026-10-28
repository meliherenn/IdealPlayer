/* T8211 regression against the actual shipped libvlc.so's public Libgcrypt API.
 * Synthetic short Curve25519 opaque points must decode exactly like zero-padded
 * full-width points. No network, provider data, or standalone replacement library.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <gcrypt.h>

#define API(name) __typeof__(&name) p_##name = (__typeof__(&name)) dlsym(lib, #name); \
    if (!p_##name) { fprintf(stderr, "Missing symbol: %s\n", #name); return 2; }

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    API(gcry_check_version);
    API(gcry_mpi_ec_new);
    API(gcry_mpi_set_opaque_copy);
    API(gcry_mpi_point_new);
    API(gcry_mpi_new);
    API(gcry_mpi_ec_decode_point);
    API(gcry_mpi_point_get);
    API(gcry_mpi_cmp);
    API(gcry_mpi_release);
    API(gcry_mpi_point_release);
    API(gcry_ctx_release);
    if (!p_gcry_check_version(NULL)) return 2;
    gcry_ctx_t ctx = NULL;
    if (p_gcry_mpi_ec_new(&ctx, NULL, "Curve25519")) return 2;
    unsigned int passed = 0;
    for (unsigned int length = 1; length <= 32; length++) {
        unsigned char short_value[32], padded[32] = {0};
        for (unsigned int j = 0; j < length; j++) short_value[j] = (j + 9) & 0x3f;
        memcpy(padded + 32 - length, short_value, length);
        gcry_mpi_t a = p_gcry_mpi_set_opaque_copy(NULL, short_value, length * 8);
        gcry_mpi_t b = p_gcry_mpi_set_opaque_copy(NULL, padded, 256);
        gcry_mpi_point_t pa = p_gcry_mpi_point_new(0), pb = p_gcry_mpi_point_new(0);
        gcry_mpi_t ax = p_gcry_mpi_new(0), bx = p_gcry_mpi_new(0);
        gcry_error_t ea = p_gcry_mpi_ec_decode_point(pa, a, ctx);
        gcry_error_t eb = p_gcry_mpi_ec_decode_point(pb, b, ctx);
        p_gcry_mpi_point_get(ax, NULL, NULL, pa);
        p_gcry_mpi_point_get(bx, NULL, NULL, pb);
        int equal = p_gcry_mpi_cmp(ax, bx) == 0;
        p_gcry_mpi_release(a); p_gcry_mpi_release(b);
        p_gcry_mpi_release(ax); p_gcry_mpi_release(bx);
        p_gcry_mpi_point_release(pa); p_gcry_mpi_point_release(pb);
        if (ea || eb || !equal) {
            fprintf(stderr, "FAIL length=%u errors=%u,%u equal=%d\n", length, ea, eb, equal);
            return 1;
        }
        passed++;
    }
    p_gcry_ctx_release(ctx);
    printf("PASS T8211: %u short/full-width point comparisons in linked Libgcrypt %s\n",
           passed, p_gcry_check_version(NULL));
    dlclose(lib);
    return 0;
}
