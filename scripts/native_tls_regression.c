/* Exercise the exact bundled GnuTLS over a synthetic, localhost-forwarded HTTPS
 * connection. The temporary CA is supplied explicitly; system trust is unchanged. */
#include <arpa/inet.h>
#include <dlfcn.h>
#include <gnutls/gnutls.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#define API(name) __typeof__(&name) p_##name = (__typeof__(&name))dlsym(lib, #name); \
    if (!p_##name) { fprintf(stderr, "Missing %s\n", #name); return 2; }

int main(int argc, char **argv) {
    if (argc != 6) { fprintf(stderr, "library ca.pem port hostname accept|reject\n"); return 2; }
    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    API(gnutls_check_version); API(gnutls_global_init); API(gnutls_global_deinit);
    API(gnutls_certificate_allocate_credentials); API(gnutls_certificate_free_credentials);
    API(gnutls_certificate_set_x509_trust_file); API(gnutls_init); API(gnutls_deinit);
    API(gnutls_priority_set_direct); API(gnutls_credentials_set); API(gnutls_server_name_set);
    API(gnutls_certificate_verify_peers3); API(gnutls_transport_set_int2);
    API(gnutls_handshake); API(gnutls_record_send); API(gnutls_record_recv);
    API(gnutls_strerror);
    API(gnutls_protocol_get_version); API(gnutls_protocol_get_name); API(gnutls_bye);
    const char *version = p_gnutls_check_version(NULL);
    if (!version || strcmp(version, "3.8.13") != 0) { fprintf(stderr, "Unexpected GnuTLS version\n"); return 2; }
    if (p_gnutls_global_init() != 0) return 2;
    gnutls_certificate_credentials_t credentials;
    if (p_gnutls_certificate_allocate_credentials(&credentials) != 0) return 2;
    if (p_gnutls_certificate_set_x509_trust_file(credentials, argv[2], GNUTLS_X509_FMT_PEM) < 1) return 2;
    gnutls_session_t session;
    if (p_gnutls_init(&session, GNUTLS_CLIENT) != 0) return 2;
    if (p_gnutls_priority_set_direct(session, "NORMAL:-VERS-ALL:+VERS-TLS1.3", NULL) != 0) return 2;
    if (p_gnutls_credentials_set(session, GNUTLS_CRD_CERTIFICATE, credentials) != 0) return 2;
    if (p_gnutls_server_name_set(session, GNUTLS_NAME_DNS, argv[4], strlen(argv[4])) != 0) return 2;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct timeval timeout = {10, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_in address = {0}; address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = htons((unsigned short)atoi(argv[3]));
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) { perror("connect"); return 2; }
    p_gnutls_transport_set_int2(session, fd, fd);
    int result;
    do { result = p_gnutls_handshake(session); } while (result == GNUTLS_E_INTERRUPTED || result == GNUTLS_E_AGAIN);
    unsigned int status = 0;
    int verified = result == 0 ? p_gnutls_certificate_verify_peers3(session, argv[4], &status) : -1;
    int passed = 0;
    if (strcmp(argv[5], "reject") == 0) {
        passed = result == 0 && verified == 0 && status != 0;
    } else if (strcmp(argv[5], "accept") == 0 && result == 0 && verified == 0 && status == 0) {
        const char request[] = "GET /owned-fixture HTTP/1.0\r\nHost: fixture.idealplayer.invalid\r\n\r\n";
        char response[512] = {0};
        if (p_gnutls_record_send(session, request, sizeof(request) - 1) > 0) {
            ssize_t length;
            int retries = 0;
            do { length = p_gnutls_record_recv(session, response, sizeof(response) - 1); }
            while ((length == GNUTLS_E_AGAIN || length == GNUTLS_E_INTERRUPTED) && ++retries < 16);
            if (length < 0) fprintf(stderr, "HTTP receive: %s (%zd)\n", p_gnutls_strerror((int)length), length);
            passed = length > 0 && strstr(response, "200 OK") != NULL;
        }
    }
    printf("%s GnuTLS=%s protocol=%s handshake=%d certificate_status=%u expected=%s\n",
           passed ? "PASS" : "FAIL", version,
           p_gnutls_protocol_get_name(p_gnutls_protocol_get_version(session)), result, status, argv[5]);
    if (result == 0) p_gnutls_bye(session, GNUTLS_SHUT_WR);
    p_gnutls_deinit(session); p_gnutls_certificate_free_credentials(credentials);
    p_gnutls_global_deinit(); close(fd); dlclose(lib);
    return passed ? 0 : 1;
}
