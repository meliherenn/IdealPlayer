/* Synthetic packet bounds regression; no network, files or provider data. */
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "smb2.h"
#include "libsmb2.h"
#include "libsmb2-private.h"
extern void smb2_free_data(struct smb2_context *, void *);
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d\n",__LINE__);return 1;} } while(0)
static void le32(uint8_t *p, uint32_t n) {p[0]=n;p[1]=n>>8;p[2]=n>>16;p[3]=n>>24;}
int main(void) {
    struct smb2_context *ctx=smb2_init_context();CHECK(ctx);
    uint8_t wire[128]={0};struct smb2_iovec v={0};v.buf=wire;
    void *mem=smb2_alloc_init(ctx,64);CHECK(mem);
    struct smb2_file_all_info all;struct smb2_file_fs_volume_info volume;
    memset(&all,0,sizeof(all));memset(&volume,0,sizeof(volume));
    v.len=99;CHECK(smb2_decode_file_all_info(ctx,mem,&all,&v)==-1);
    v.len=100;le32(wire+96,0x10000);CHECK(smb2_decode_file_all_info(ctx,mem,&all,&v)==-1);
    le32(wire+96,4);CHECK(smb2_decode_file_all_info(ctx,mem,&all,&v)==-1);
    v.len=104;le32(wire+96,3);CHECK(smb2_decode_file_all_info(ctx,mem,&all,&v)==-1);
    le32(wire+96,4);wire[100]='A';wire[102]='B';CHECK(smb2_decode_file_all_info(ctx,mem,&all,&v)==0);CHECK(!strcmp((const char *)all.name,"AB"));
    memset(wire,0,sizeof(wire));v.len=17;CHECK(smb2_decode_file_fs_volume_info(ctx,mem,&volume,&v)==-1);
    v.len=18;le32(wire+12,4);CHECK(smb2_decode_file_fs_volume_info(ctx,mem,&volume,&v)==-1);
    v.len=22;le32(wire+12,3);CHECK(smb2_decode_file_fs_volume_info(ctx,mem,&volume,&v)==-1);
    le32(wire+12,4);wire[18]='C';wire[20]='D';CHECK(smb2_decode_file_fs_volume_info(ctx,mem,&volume,&v)==0);CHECK(!strcmp((const char *)volume.volume_label,"CD"));
    struct smb2_security_descriptor *sd=smb2_alloc_init(ctx,sizeof(*sd));CHECK(sd);
    memset(wire,0,sizeof(wire));wire[0]=1;v.len=28;le32(wire+16,20);wire[20]=2;wire[22]=4;
    CHECK(smb2_decode_security_descriptor(ctx,sd,sd,&v)==-1); /* ACL size < fixed header */
    memset(sd,0,sizeof(*sd));le32(wire+16,0xfffffffc);v.len=20;
    CHECK(smb2_decode_security_descriptor(ctx,sd,sd,&v)==0);CHECK(sd->dacl==NULL); /* Wrapped offset must be ignored, never dereferenced. */
    struct {struct smb2_io_vectors vectors;uint64_t canary;} *test=calloc(1,sizeof(*test));CHECK(test);
    test->canary=UINT64_C(0xabcdef0198765432);
    for(int i=0;i<SMB2_MAX_VECTORS;i++) CHECK(smb2_add_iovector(ctx,&test->vectors,wire,1,NULL)==&test->vectors.iov[i]);
    CHECK(test->vectors.niov==SMB2_MAX_VECTORS);
    CHECK(smb2_add_iovector(ctx,&test->vectors,wire,1,NULL)==&test->vectors.iov[SMB2_MAX_VECTORS-1]);
    CHECK(test->vectors.niov==SMB2_MAX_VECTORS && test->canary==UINT64_C(0xabcdef0198765432));
    free(test);smb2_free_data(ctx,sd);smb2_free_data(ctx,mem);smb2_destroy_context(ctx);
    puts("PASS SMB packet lengths, UTF-16 valid replies, ACL bounds, wrapped offsets and 256-vector boundary");return 0;
}
