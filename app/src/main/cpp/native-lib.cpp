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
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(JNIEnv* env, jobject, jstring inPath, jstring outPath, jint scaleDenom) {
    if (nativeLutSize == 0) return JNI_FALSE;
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

    memset(cinfo_d, 0, sizeof(struct jpeg_decompress_struct));
    memset(cinfo_c, 0, sizeof(struct jpeg_compress_struct));

    cinfo_d->err = jpeg_std_error(&jerr_d->pub);
    jerr_d->pub.error_exit = my_error_exit;
    jerr_d->pub.emit_message = my_emit_message; jerr_d->pub.output_message = my_output_message;
    
    if (setjmp(jerr_d->setjmp_buffer)) {
        jpeg_destroy_decompress(cinfo_d); free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
        fclose(infile); fclose(outfile); return JNI_FALSE;
    }
    
    jpeg_create_decompress(cinfo_d);

    // CRITICAL: Tell libjpeg to keep the EXIF breadcrumbs from the original file
    jpeg_save_markers(cinfo_d, JPEG_APP0 + 1, 0xFFFF); 

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
        fclose(infile); fclose(outfile); return JNI_FALSE;
    }
    
    jpeg_create_compress(cinfo_c);
    jpeg_stdio_dest(cinfo_c, outfile);
    cinfo_c->image_width = cinfo_d->output_width;
    cinfo_c->image_height = cinfo_d->output_height;
    cinfo_c->input_components = 3;
    cinfo_c->in_color_space = JCS_RGB;
    jpeg_set_defaults(cinfo_c);
    jpeg_set_quality(cinfo_c, 95, TRUE);

    // CRITICAL: Blindly copy the original EXIF/Thumbnail into the cooked file
    jpeg_saved_marker_ptr marker = cinfo_d->marker_list;
    while (marker != NULL) {
        jpeg_write_marker(cinfo_c, marker->marker, marker->data, marker->data_length);
        marker = marker->next;
    }

    jpeg_start_compress(cinfo_c, TRUE);

    int lutMax = nativeLutSize - 1;
    int lutSize2 = nativeLutSize * nativeLutSize;
    int row_stride = cinfo_d->output_width * cinfo_d->output_components;
    JSAMPARRAY buffer = (*cinfo_d->mem->alloc_sarray)((j_common_ptr) cinfo_d, JPOOL_IMAGE, row_stride, 1);

    const int* pR = &nativeLutR[0]; const int* pG = &nativeLutG[0]; const int* pB = &nativeLutB[0];
    for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }

    while (cinfo_d->output_scanline < cinfo_d->output_height) {
        jpeg_read_scanlines(cinfo_d, buffer, 1);
        unsigned char* row = buffer[0];
        for (int x = 0; x < row_stride; x += 3) {
            float r_f = (row[x] / 255.0f) * lutMax;
            float g_f = (row[x+1] / 255.0f) * lutMax;
            float b_f = (row[x+2] / 255.0f) * lutMax;
            int r0 = (int)r_f; int g0 = (int)g_f; int b0 = (int)b_f;
            float dr = r_f - r0; float dg = g_f - g0; float db = b_f - b0;
            int i000 = r0 + g0 * nativeLutSize + b0 * lutSize2;
            int oR, oG, oB;
            // High-Performance Tetrahedral Logic
            if (dr > dg) {
                if (dg > db) { 
                    int i100 = (r0+1) + g0 * nativeLutSize + b0 * lutSize2;
                    int i110 = (r0+1) + (g0+1) * nativeLutSize + b0 * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + dr*(pR[i100]-pR[i000]) + dg*(pR[i110]-pR[i100]) + db*(pR[i111]-pR[i110]);
                    oG = pG[i000] + dr*(pG[i100]-pG[i000]) + dg*(pG[i110]-pG[i000]) + db*(pG[i111]-pG[i110]);
                    oB = pB[i000] + dr*(pB[i100]-pB[i000]) + dg*(pB[i110]-pB[i000]) + db*(pB[i111]-pB[i110]);
                } else if (dr > db) {
                    int i100 = (r0+1) + g0 * nativeLutSize + b0 * lutSize2;
                    int i101 = (r0+1) + g0 * nativeLutSize + (b0+1) * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + dr*(pR[i100]-pR[i000]) + db*(pR[i101]-pR[i100]) + dg*(pR[i111]-pR[i101]);
                    oG = pG[i000] + dr*(pG[i100]-pG[i000]) + db*(pG[i101]-pG[i100]) + dg*(pG[i111]-pG[i101]);
                    oB = pB[i000] + dr*(pB[i100]-pB[i000]) + db*(pB[i101]-pB[i100]) + dg*(pB[i111]-pB[i101]);
                } else {
                    int i001 = r0 + g0 * nativeLutSize + (b0+1) * lutSize2;
                    int i101 = (r0+1) + g0 * nativeLutSize + (b0+1) * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + db*(pR[i001]-pR[i000]) + dr*(pR[i101]-pR[i001]) + dg*(pR[i111]-pR[i101]);
                    oG = pG[i000] + db*(pG[i001]-pG[i000]) + dr*(pG[i101]-pG[i001]) + dg*(pG[i111]-pG[i101]);
                    oB = pB[i000] + db*(pB[i001]-pB[i000]) + dr*(pB[i101]-pB[i001]) + dg*(pB[i111]-pB[i101]);
                }
            } else {
                if (db > dg) {
                    int i001 = r0 + g0 * nativeLutSize + (b0+1) * lutSize2;
                    int i011 = r0 + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + db*(pR[i001]-pR[i000]) + dg*(pR[i011]-pR[i001]) + dr*(pR[i111]-pR[i011]);
                    oG = pG[i000] + db*(pG[i001]-pG[i000]) + dg*(pG[i011]-pG[i001]) + dr*(pG[i111]-pG[i011]);
                    oB = pB[i000] + db*(pB[i001]-pB[i000]) + dg*(pB[i011]-pB[i001]) + dr*(pB[i111]-pB[i011]);
                } else if (db > dr) {
                    int i010 = r0 + (g0+1) * nativeLutSize + b0 * lutSize2;
                    int i011 = r0 + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + dg*(pR[i010]-pR[i000]) + db*(pR[i011]-pR[i010]) + dr*(pR[i111]-pR[i011]);
                    oG = pG[i000] + dg*(pG[i010]-pG[i000]) + db*(pG[i011]-pG[i010]) + dr*(pG[i111]-pG[i011]);
                    oB = pB[i000] + dg*(pB[i010]-pB[i000]) + db*(pB[i011]-pB[i010]) + dr*(pB[i111]-pB[i011]);
                } else {
                    int i010 = r0 + (g0+1) * nativeLutSize + b0 * lutSize2;
                    int i110 = (r0+1) + (g0+1) * nativeLutSize + b0 * lutSize2;
                    int i111 = (r0+1) + (g0+1) * nativeLutSize + (b0+1) * lutSize2;
                    oR = pR[i000] + dg*(pR[i010]-pR[i000]) + dr*(pR[i110]-pR[i010]) + db*(pR[i111]-pR[i110]);
                    oG = pG[i000] + dg*(pG[i010]-pG[i000]) + dr*(pG[i110]-pG[i010]) + db*(pG[i111]-pG[i110]);
                    oB = pB[i000] + dg*(pB[i010]-pB[i000]) + dr*(pB[i110]-pB[i010]) + db*(pB[i111]-pB[i110]);
                }
            }
            row[x] = (unsigned char)(oR < 0 ? 0 : (oR > 255 ? 255 : oR));
            row[x+1] = (unsigned char)(oG < 0 ? 0 : (oG > 255 ? 255 : oG));
            row[x+2] = (unsigned char)(oB < 0 ? 0 : (oB > 255 ? 255 : oB));
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