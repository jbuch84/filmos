#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "COOKBOOK_LOG"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<int> nativeLutR, nativeLutG, nativeLutB;
int nativeLutSize = 0;
uint32_t random_seed = 12345;

struct my_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}
METHODDEF(void) my_emit_message (j_common_ptr cinfo, int msg_level) {}
METHODDEF(void) my_output_message (j_common_ptr cinfo) {}

// Extremely fast pseudo-random number generator
inline int fast_rand() {
    random_seed = (214013 * random_seed + 2531011);
    return (random_seed >> 16) & 0xFF;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    env->ReleaseStringUTFChars(path, file_path);
    if (!file) return JNI_FALSE;

    nativeLutR.clear(); nativeLutG.clear(); nativeLutB.clear();
    nativeLutSize = 0;

    char line[256];
    while(fgets(line, sizeof(line), file)) {
        if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
            sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
            int expected = nativeLutSize * nativeLutSize * nativeLutSize;
            nativeLutR.reserve(expected); nativeLutG.reserve(expected); nativeLutB.reserve(expected);
        }
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
            nativeLutR.push_back((int)(r * 255.0f));
            nativeLutG.push_back((int)(g * 255.0f));
            nativeLutB.push_back((int)(b * 255.0f));
        }
    }
    fclose(file);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(JNIEnv* env, jobject, jstring inPath, jstring outPath, jint scaleDenom, jint opacity, jint grain, jint vignette, jint rolloff) {
    
    bool hasLut = (nativeLutSize > 0);

    const char *in_file = env->GetStringUTFChars(inPath, NULL);
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    FILE *infile = fopen(in_file, "rb");
    FILE *outfile = fopen(out_file, "wb");
    if (!infile || !outfile) {
        if (infile) fclose(infile); if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }

    struct jpeg_decompress_struct* cinfo_d = (struct jpeg_decompress_struct*) malloc(sizeof(struct jpeg_decompress_struct));
    struct my_error_mgr* jerr_d = (struct my_error_mgr*) malloc(sizeof(struct my_error_mgr));
    struct jpeg_compress_struct* cinfo_c = (struct jpeg_compress_struct*) malloc(sizeof(struct jpeg_compress_struct));
    struct my_error_mgr* jerr_c = (struct my_error_mgr*) malloc(sizeof(struct my_error_mgr));
    int* map = (int*) malloc(256 * sizeof(int));

    if (!cinfo_d || !jerr_d || !cinfo_c || !jerr_c || !map) {
        if (infile) fclose(infile); if (outfile) fclose(outfile);
        return JNI_FALSE;
    }

    memset(cinfo_d, 0, sizeof(struct jpeg_decompress_struct));
    memset(cinfo_c, 0, sizeof(struct jpeg_compress_struct));

    cinfo_d->err = jpeg_std_error(&jerr_d->pub);
    jerr_d->pub.error_exit = my_error_exit;
    jerr_d->pub.emit_message = my_emit_message; jerr_d->pub.output_message = my_output_message;
    
    if (setjmp(jerr_d->setjmp_buffer)) {
        jpeg_destroy_decompress(cinfo_d); free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
        fclose(infile); fclose(outfile); 
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }
    
    jpeg_create_decompress(cinfo_d);
    jpeg_stdio_src(cinfo_d, infile);
    jpeg_read_header(cinfo_d, TRUE);
    cinfo_d->scale_num = 1;
    cinfo_d->scale_denom = scaleDenom;
    cinfo_d->out_color_space = JCS_RGB; 
    jpeg_start_decompress(cinfo_d);

    cinfo_c->err = jpeg_std_error(&jerr_c->pub);
    jerr_c->pub.error_exit = my_error_exit;
    jerr_c->pub.emit_message = my_emit_message; jerr_c->pub.output_message = my_output_message;
    
    if (setjmp(jerr_c->setjmp_buffer)) {
        jpeg_destroy_compress(cinfo_c); jpeg_destroy_decompress(cinfo_d);
        free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
        fclose(infile); fclose(outfile); 
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }
    
    jpeg_create_compress(cinfo_c);
    jpeg_stdio_dest(cinfo_c, outfile);
    cinfo_c->image_width = cinfo_d->output_width;
    cinfo_c->image_height = cinfo_d->output_height;
    cinfo_c->input_components = 3;
    cinfo_c->in_color_space = JCS_RGB;
    jpeg_set_defaults(cinfo_c);
    jpeg_set_quality(cinfo_c, 95, TRUE);
    jpeg_start_compress(cinfo_c, TRUE);

    int lutMax = nativeLutSize > 0 ? nativeLutSize - 1 : 0;
    int lutSize2 = nativeLutSize * nativeLutSize;
    for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }
    
    int row_stride = cinfo_d->output_width * cinfo_d->output_components;
    JSAMPARRAY buffer = (*cinfo_d->mem->alloc_sarray)((j_common_ptr) cinfo_d, JPOOL_IMAGE, row_stride, 1);

    const int* pR = hasLut ? &nativeLutR[0] : NULL; 
    const int* pG = hasLut ? &nativeLutG[0] : NULL; 
    const int* pB = hasLut ? &nativeLutB[0] : NULL;

    long long cx = cinfo_d->output_width / 2;
    long long cy = cinfo_d->output_height / 2;
    long long max_dist_sq = cx*cx + cy*cy;
    if (max_dist_sq == 0) max_dist_sq = 1;

    // Convert 0-100 UI sliders to optimal math ranges
    int vig_mapped = (vignette * 256) / 100;
    int opac_mapped = (opacity * 256) / 100;

    while (cinfo_d->output_scanline < cinfo_d->output_height) {
        long long current_y = cinfo_d->output_scanline;
        jpeg_read_scanlines(cinfo_d, buffer, 1);
        unsigned char* row = buffer[0];
        long long dy = current_y - cy;
        long long dy_sq = dy * dy;

        for (int x = 0; x < row_stride; x += 3) {
            int origR = row[x]; int origG = row[x+1]; int origB = row[x+2];
            int outR = origR, outG = origG, outB = origB;

            // 1. LUT INTERPOLATION & OPACITY (Bypassed if "NONE" is selected)
            if (hasLut) {
                int fX = map[origR]; int fY = map[origG]; int fZ = map[origB];
                int x0 = fX >> 7; int y0 = fY >> 7; int z0 = fZ >> 7;
                int x1 = x0 + 1; if (x1 > lutMax) x1 = lutMax;
                int y1 = y0 + 1; if (y1 > lutMax) y1 = lutMax;
                int z1 = z0 + 1; if (z1 > lutMax) z1 = lutMax;

                int dx = fX & 0x7F; int dy = fY & 0x7F; int dz = fZ & 0x7F;
                int y0_idx = y0 * nativeLutSize; int y1_idx = y1 * nativeLutSize;
                int z0_idx = z0 * lutSize2;      int z1_idx = z1 * lutSize2;

                int i000 = x0 + y0_idx + z0_idx; int i100 = x1 + y0_idx + z0_idx;
                int i010 = x0 + y1_idx + z0_idx; int i110 = x1 + y1_idx + z0_idx;
                int i001 = x0 + y0_idx + z1_idx; int i101 = x1 + y0_idx + z1_idx;
                int i011 = x0 + y1_idx + z1_idx; int i111 = x1 + y1_idx + z1_idx;

                int v0, v1, v2, v3, w0, w1, w2, w3;
                if (dx >= dy) {
                    if (dy >= dz) { v0=i000; v1=i100; v2=i110; v3=i111; w0=128-dx; w1=dx-dy; w2=dy-dz; w3=dz; } 
                    else if (dx >= dz) { v0=i000; v1=i100; v2=i101; v3=i111; w0=128-dx; w1=dx-dz; w2=dz-dy; w3=dy; } 
                    else { v0=i000; v1=i001; v2=i101; v3=i111; w0=128-dz; w1=dz-dx; w2=dx-dy; w3=dy; }
                } else {
                    if (dz >= dy) { v0=i000; v1=i001; v2=i011; v3=i111; w0=128-dz; w1=dz-dy; w2=dy-dx; w3=dx; } 
                    else if (dz >= dx) { v0=i000; v1=i010; v2=i011; v3=i111; w0=128-dy; w1=dy-dz; w2=dz-dx; w3=dx; } 
                    else { v0=i000; v1=i010; v2=i110; v3=i111; w0=128-dy; w1=dy-dx; w2=dx-dz; w3=dz; }
                }

                int lutR = (pR[v0]*w0 + pR[v1]*w1 + pR[v2]*w2 + pR[v3]*w3) >> 7;
                int lutG = (pG[v0]*w0 + pG[v1]*w1 + pG[v2]*w2 + pG[v3]*w3) >> 7;
                int lutB = (pB[v0]*w0 + pB[v1]*w1 + pB[v2]*w2 + pB[v3]*w3) >> 7;

                if (opacity < 100) {
                    outR = origR + (((lutR - origR) * opac_mapped) >> 8);
                    outG = origG + (((lutG - origG) * opac_mapped) >> 8);
                    outB = origB + (((lutB - origB) * opac_mapped) >> 8);
                } else {
                    outR = lutR; outG = lutG; outB = lutB;
                }
            }

            // 2. HIGHLIGHT ROLL-OFF (Soft Shoulder applied to over-bright pixels)
            if (rolloff > 0) {
                if (outR > 200) outR -= ((outR - 200) * (outR - 200) * rolloff) / 11000;
                if (outG > 200) outG -= ((outG - 200) * (outG - 200) * rolloff) / 11000;
                if (outB > 200) outB -= ((outB - 200) * (outB - 200) * rolloff) / 11000;
            }

            // 3. OPTICAL VIGNETTE
            if (vignette > 0) {
                long long cur_dx = (x / 3) - cx;
                long long dist_sq = cur_dx*cur_dx + dy_sq;
                int vig_mult = 256 - (int)((dist_sq * (long long)vig_mapped) / max_dist_sq);
                if (vig_mult < 0) vig_mult = 0;
                outR = (outR * vig_mult) >> 8;
                outG = (outG * vig_mult) >> 8;
                outB = (outB * vig_mult) >> 8;
            }

            // 4. AUTHENTIC 35MM GAUSSIAN GRAIN
            if (grain > 0) {
                // Summing 3 random values approximates a Gaussian Bell Curve (Smooth texture)
                int n1 = fast_rand(); int n2 = fast_rand(); int n3 = fast_rand();
                int noise = ((n1 + n2 + n3) / 3) - 128; // -128 to +127 
                
                int lum = (outR*77 + outG*150 + outB*29) >> 8; 
                int mask = 128 - abs(lum - 128); // 0 at pure black/white, 128 at mid-gray
                
                // Monochromatic noise addition (preserves color hues perfectly)
                int grain_val = (noise * mask * grain) >> 15; 
                outR += grain_val;
                outG += grain_val;
                outB += grain_val;
            }

            // 5. HARD CLAMP (Safety Net)
            row[x]   = (unsigned char)(outR < 0 ? 0 : (outR > 255 ? 255 : outR));
            row[x+1] = (unsigned char)(outG < 0 ? 0 : (outG > 255 ? 255 : outG));
            row[x+2] = (unsigned char)(outB < 0 ? 0 : (outB > 255 ? 255 : outB));
        }
        jpeg_write_scanlines(cinfo_c, buffer, 1);
    }

    jpeg_finish_compress(cinfo_c); jpeg_destroy_compress(cinfo_c);
    jpeg_finish_decompress(cinfo_d); jpeg_destroy_decompress(cinfo_d);
    free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
    fclose(infile); fclose(outfile);
    env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
    return JNI_TRUE;
}