/* SPDX-License-Identifier: MIT
 * Focused libpng/libxml2/AOM checks linked to the actual shipped LibVLC DSO.
 * Fixtures are generated locally; no provider data or external network is used.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <setjmp.h>
#include <png.h>
#include <libxml/parser.h>
#include <libxml/dict.h>
#include <aom/aom_image.h>
#define REQUIRE(x) do { if (!(x)) { fprintf(stderr,"FAIL %s:%d: %s\n",__FILE__,__LINE__,#x); exit(1); } } while(0)
static int read_png(const char *path, unsigned expected_width) {
    FILE *f=fopen(path,"rb"); REQUIRE(f);
    png_structp p=png_create_read_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL); REQUIRE(p);
    png_infop info=png_create_info_struct(p); REQUIRE(info);
    if (setjmp(png_jmpbuf(p))) { png_destroy_read_struct(&p,&info,NULL); fclose(f); return 0; }
    png_init_io(p,f); png_read_info(p,info);
    REQUIRE(png_get_image_width(p,info)==expected_width && png_get_image_height(p,info)==1);
    png_set_palette_to_rgb(p); png_set_tRNS_to_alpha(p); png_read_update_info(p,info);
    size_t n=png_get_rowbytes(p,info); REQUIRE(n==expected_width*4 && n<=132);
    unsigned char storage[164]; memset(storage,0xa5,sizeof storage);
    png_read_row(p,storage+16,NULL); png_read_end(p,info);
    for(unsigned i=0;i<16;i++) REQUIRE(storage[i]==0xa5 && storage[n+16+i]==0xa5);
    for(unsigned i=0;i<expected_width;i++) {
        unsigned char *pixel=storage+16+4*i;
        REQUIRE(pixel[0]==(i%2?0:255) && pixel[1]==(i%2?255:0) && pixel[2]==0 && pixel[3]==(i%2?128:255));
    }
    png_destroy_read_struct(&p,&info,NULL); fclose(f); return 1;
}
static void xml_checks(void) {
    REQUIRE(strcmp(xmlParserVersion,"21504")==0);
    const char *good="<tv xmlns:p='urn:synthetic'><p:programme channel='1'>A &amp; B</p:programme></tv>";
    xmlDocPtr doc=xmlReadMemory(good,(int)strlen(good),"synthetic.xml",NULL,XML_PARSE_NONET); REQUIRE(doc);
    xmlNodePtr root=xmlDocGetRootElement(doc); REQUIRE(root && xmlStrEqual(root->name,BAD_CAST"tv"));
    xmlChar *value=xmlNodeGetContent(root); REQUIRE(value && xmlStrEqual(value,BAD_CAST"A & B")); xmlFree(value); xmlFreeDoc(doc);
    const char *bad="<tv><programme></tv>";doc=xmlReadMemory(bad,(int)strlen(bad),"invalid.xml",NULL,XML_PARSE_NONET|XML_PARSE_NOERROR|XML_PARSE_NOWARNING);REQUIRE(!doc);
    xmlDictPtr dict=xmlDictCreate();REQUIRE(dict);
    const xmlChar *a=xmlDictQLookup(dict,BAD_CAST"prefix",BAD_CAST"name"); REQUIRE(a && xmlStrEqual(a,BAD_CAST"prefix:name"));
    REQUIRE(a==xmlDictQLookup(dict,BAD_CAST"prefix",BAD_CAST"name"));
    REQUIRE(xmlStrEqual(xmlDictQLookup(dict,NULL,BAD_CAST"local"),BAD_CAST"local"));
    char longname[8193]; memset(longname,'x',8192);longname[8192]=0;
    REQUIRE(xmlDictQLookup(dict,BAD_CAST"p",BAD_CAST longname));xmlDictFree(dict);
    puts("PASS XML valid namespaces/entities, malformed rejection, QName dictionary reuse and long names");
}
static void aom_checks(void) {
    aom_image_t img;
    REQUIRE(aom_img_alloc(&img,AOM_IMG_FMT_I420,17,9,16)); REQUIRE(img.d_w==17 && img.d_h==9);aom_img_free(&img);
    REQUIRE(!aom_img_alloc(&img,AOM_IMG_FMT_I420,0x08000001u,1,1));
    REQUIRE(!aom_img_alloc(&img,AOM_IMG_FMT_I420,1,0x08000001u,1));
    REQUIRE(!aom_img_alloc(&img,AOM_IMG_FMT_I420,16,16,65537));
    REQUIRE(!aom_img_alloc(&img,AOM_IMG_FMT_I420,UINT32_MAX,UINT32_MAX,16));
    unsigned char tiny[1024];REQUIRE(aom_img_wrap(&img,AOM_IMG_FMT_I420,16,16,1,tiny));aom_img_free(&img);
    REQUIRE(!aom_img_wrap(&img,AOM_IMG_FMT_I420,UINT32_MAX,1,1,tiny));
    puts("PASS AOM valid allocation/wrap and dimension/alignment overflow rejection");
}
int main(int argc,char **argv) {
    REQUIRE(argc==2); REQUIRE(png_access_version_number()==10659);
    unsigned widths[]={1,2,3,7,8,9,15,16,17,33}; char path[1024];
    for(unsigned i=0;i<sizeof(widths)/sizeof(widths[0]);i++) { snprintf(path,sizeof path,"%s/palette-%u.png",argv[1],widths[i]);REQUIRE(read_png(path,widths[i])); }
    snprintf(path,sizeof path,"%s/malformed-text.png",argv[1]); (void)read_png(path,17);
    snprintf(path,sizeof path,"%s/truncated.png",argv[1]);REQUIRE(!read_png(path,17));
    puts("PASS PNG palette/transparency at NEON boundaries, pixel values, output canaries and malformed chunks");
    xml_checks();aom_checks();puts("PASS all targeted image/XML checks");return 0;
}
