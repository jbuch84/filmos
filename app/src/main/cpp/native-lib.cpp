#include <jni.h>
#include <vector>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include "jpeglib.h"
#include <android/log.h>
#include "process_kernel.h"
#include <pthread.h>
#include <functional>
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct ThreadTask {
    std::function<void()> func;
};

struct ThreadWorkspace {
    int* work_0; int* work_1; int* work_2; int* work_h; int* h_line;
};

static void* thread_runner(void* arg) {
    ThreadTask* t = static_cast<ThreadTask*>(arg);
    t->func();
    return NULL;
}

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;

// NEW: Global for our external PNG/JPG grain texture
std::vector<uint8_t> nativeGrainTexture;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

long long get_time_ms() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    nativeLut.clear();
    nativeLutSize = 0;

    const char *file_path = env->GetStringUTFChars(path, NULL);
    std::string path_str(file_path);

    std::string ext = "";
    size_t dot_pos = path_str.find_last_of('.');
    if (dot_pos != std::string::npos) {
        ext = path_str.substr(dot_pos);
        for(size_t i = 0; i < ext.length(); i++) ext[i] = tolower(ext[i]);
    }

    if (ext == ".png") {
        int w, h, c;
        unsigned char *img_data = stbi_load(file_path, &w, &h, &c, 3);
        if (img_data) {
            int total_pixels = w * h;
            if (total_pixels > 4000000) {
                nativeLutSize = 0;
            } else {
                int best_level = 1;
                int min_diff = total_pixels;
                for (int l = 1; l <= 150; l++) {
                    int diff = (l * l * l) - total_pixels;
                    if (diff < 0) diff = -diff;
                    if (diff < min_diff) {
                        min_diff = diff;
                        best_level = l;
                    }
                }
                nativeLutSize = best_level;
                int total_bytes = nativeLutSize * nativeLutSize * nativeLutSize * 3;
                nativeLut.resize(total_bytes);
                int tiles_per_row = w / nativeLutSize;
                if (tiles_per_row == 0) tiles_per_row = 1;
                for (int b = 0; b < nativeLutSize; b++) {
                    int cell_x = b % tiles_per_row;
                    int cell_y = b / tiles_per_row;
                    for (int g = 0; g < nativeLutSize; g++) {
                        int img_y = cell_y * nativeLutSize + g;
                        for (int r = 0; r < nativeLutSize; r++) {
                            int img_x = cell_x * nativeLutSize + r;
                            if (img_x >= w) img_x = w - 1;
                            if (img_y >= h) img_y = h - 1;
                            int src_idx = (img_y * w + img_x) * 3;
                            int dst_idx = (r + g * nativeLutSize + b * nativeLutSize * nativeLutSize) * 3;
                            nativeLut[dst_idx]     = img_data[src_idx];
                            nativeLut[dst_idx + 1] = img_data[src_idx + 1];
                            nativeLut[dst_idx + 2] = img_data[src_idx + 2];
                        }
                    }
                }
            }
            stbi_image_free(img_data);
        }
    }
    else if (ext == ".cube" || ext == ".cub") {
        FILE *file = fopen(file_path, "r");
        if (file) {
            char line[256];
            size_t count = 0;
            while(fgets(line, sizeof(line), file)) {
                if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
                    sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
                    nativeLut.resize(nativeLutSize * nativeLutSize * nativeLutSize * 3);
                    continue;
                }
                float r, g, b;
                if (nativeLutSize > 0 && sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
                    if (count + 2 < nativeLut.size()) {
                        nativeLut[count++] = (uint8_t)(r * 255.0f);
                        nativeLut[count++] = (uint8_t)(g * 255.0f);
                        nativeLut[count++] = (uint8_t)(b * 255.0f);
                    }
                }
            }
            fclose(file);
        }
    }
    env->ReleaseStringUTFChars(path, file_path);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadGrainTextureNative(JNIEnv* env, jobject obj, jstring path) {
    nativeGrainTexture.clear();
    if (path == NULL) return JNI_FALSE;
    const char *file_path = env->GetStringUTFChars(path, NULL);
    int w, h, c;
    unsigned char *img_data = stbi_load(file_path, &w, &h, &c, 3);
    env->ReleaseStringUTFChars(path, file_path);
    if (img_data) {
        if ((w == 512 && h == 512) || (w == 1024 && h == 1024)) {
            nativeGrainTexture.assign(img_data, img_data + (w * h * 3));
            stbi_image_free(img_data);
            return JNI_TRUE;
        }
        stbi_image_free(img_data);
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath,
    jint scaleDenom, jint opacity, jint grain, jint grainSize,
    jint vignette, jint rollOff, jint colorChrome, jint chromeBlue,
    jint shadowToe, jint subtractiveSat, jint halation,
    jint bloom, jint jpegQuality, jboolean applyCrop, jint numCores) {

    long long start_time = get_time_ms();
    const char *in_file  = env->GetStringUTFChars(inPath,  NULL);
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    FILE *infile  = fopen(in_file,  "rb");
    FILE *outfile = fopen(out_file, "wb");

    if (!infile || !outfile) {
        if (infile)  fclose(infile);
        if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath,  in_file);
        env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }

    std::vector<uint8_t> exifData;
    int targetOffset = 0;
    int finalScale   = scaleDenom;
    unsigned char* header = (unsigned char*)malloc(1048576);
    if (header) {
        int readLen = fread(header, 1, 1048576, infile);
        for (int i = 0; i < readLen - 8; i++) {
            if (header[i] == 0xFF && header[i+1] == 0xE1) {
                if (header[i+4]=='E' && header[i+5]=='x' && header[i+6]=='i' && header[i+7]=='f') {
                    int len = (header[i+2] << 8) | header[i+3];
                    if (i + 2 + len <= readLen) exifData.assign(header + i + 4, header + i + 2 + len);
                    break;
                }
            }
        }
        if (scaleDenom == 4) {
            for (int i = 1000; i < readLen - 10; i++) {
                if (header[i] == 0xFF && header[i+1] == 0xD8) {
                    for (int j = i + 2; j < i + 65536 && j < readLen - 10; j++) {
                        if (header[j] == 0xFF && header[j+1] == 0xC0) {
                            int width = (header[j+7] << 8) | header[j+8];
                            if (width >= 1000 && width <= 2000) { targetOffset = i; finalScale = 1; break; }
                        }
                    }
                }
                if (finalScale == 1) break;
            }
        }
        free(header);
    }

    bool use_rgb_path = (nativeLutSize > 0 && opacity > 0);
    struct jpeg_decompress_struct cinfo_d;
    struct jpeg_compress_struct   cinfo_c;
    struct my_error_mgr jerr_d, jerr_c;

    cinfo_d.err = jpeg_std_error(&jerr_d.pub);
    jerr_d.pub.error_exit = my_error_exit;
    jpeg_create_decompress(&cinfo_d);

    bool decoded = false;
    if (scaleDenom == 4 && targetOffset > 0) {
        fseek(infile, targetOffset, SEEK_SET);
        if (!setjmp(jerr_d.setjmp_buffer)) {
            jpeg_stdio_src(&cinfo_d, infile);
            jpeg_read_header(&cinfo_d, TRUE);
            cinfo_d.scale_num = 1; cinfo_d.scale_denom = 1;
            cinfo_d.out_color_space = use_rgb_path ? JCS_RGB : JCS_YCbCr;
            jpeg_start_decompress(&cinfo_d);
            decoded = true;
        }
    }
    if (!decoded) {
        fseek(infile, 0, SEEK_SET);
        if (setjmp(jerr_d.setjmp_buffer)) {
            jpeg_destroy_decompress(&cinfo_d);
            fclose(infile); fclose(outfile);
            return JNI_FALSE;
        }
        jpeg_stdio_src(&cinfo_d, infile);
        jpeg_read_header(&cinfo_d, TRUE);
        cinfo_d.scale_num = 1; cinfo_d.scale_denom = (scaleDenom == 4) ? 4 : scaleDenom;
        cinfo_d.out_color_space = use_rgb_path ? JCS_RGB : JCS_YCbCr;
        jpeg_start_decompress(&cinfo_d);
    }

    cinfo_c.err = jpeg_std_error(&jerr_c.pub);
    jerr_c.pub.error_exit = my_error_exit;
    if (setjmp(jerr_c.setjmp_buffer)) {
        jpeg_destroy_compress(&cinfo_c); jpeg_destroy_decompress(&cinfo_d);
        fclose(infile); fclose(outfile);
        return JNI_FALSE;
    }

    jpeg_create_compress(&cinfo_c);
    jpeg_stdio_dest(&cinfo_c, outfile);
    
    int final_height = cinfo_d.output_height;
    int skip_top = 0;
    if (applyCrop) {
        final_height = (int)(cinfo_d.output_width / 2.71f);
        skip_top = (cinfo_d.output_height - final_height) / 2;
    }

    cinfo_c.image_width      = cinfo_d.output_width;
    cinfo_c.image_height     = final_height;
    cinfo_c.input_components = 3;
    cinfo_c.in_color_space   = use_rgb_path ? JCS_RGB : JCS_YCbCr;
    jpeg_set_defaults(&cinfo_c);
    jpeg_set_quality(&cinfo_c, jpegQuality, TRUE);
    jpeg_start_compress(&cinfo_c, TRUE);

    if (!exifData.empty()) jpeg_write_marker(&cinfo_c, JPEG_APP0 + 1, exifData.data(), exifData.size());

    int row_stride    = cinfo_d.output_width * 3;
    long long cx      = cinfo_d.output_width  / 2;
    long long cy_center = cinfo_d.output_height / 2;
    long long max_dist_sq = cx * cx + cy_center * cy_center;
    long long vig_coef    = get_vig_coef(vignette, max_dist_sq);
    int opac_mapped   = (opacity * 256) / 100;

    const int CHUNK_SIZE = 128; 
    const int BUFFER_SIZE = CHUNK_SIZE + 20;

    unsigned char* row_block = (unsigned char*)malloc(BUFFER_SIZE * row_stride);
    unsigned char* rows[256]; 
    for (int i = 0; i < BUFFER_SIZE; i++) rows[i] = row_block + (i * row_stride);

    unsigned char* out_block = (unsigned char*)malloc(CHUNK_SIZE * row_stride);
    unsigned char* out_rows[256]; 
    for (int i = 0; i < CHUNK_SIZE; i++) out_rows[i] = out_block + (i * row_stride);

    JSAMPROW row_pointer[1];
    int map[256];
    int lutMax   = nativeLutSize - 1;
    int lutSize2 = nativeLutSize * nativeLutSize;
    if (use_rgb_path) {
        for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }
    }
    uint8_t rolloff_lut[256];
    if (!use_rgb_path) {
        generate_rolloff_lut(rolloff_lut, rollOff);
    }

    ThreadWorkspace workspaces[16];
    int w_size = cinfo_d.output_width * sizeof(int);
    for (int i = 0; i < 16; i++) {
        workspaces[i].work_0 = (int*)malloc(w_size);
        workspaces[i].work_1 = (int*)malloc(w_size);
        workspaces[i].work_2 = (int*)malloc(w_size);
        workspaces[i].work_h = (int*)malloc(w_size);
        workspaces[i].h_line = (int*)malloc(w_size);
    }

    const uint8_t* externalTex = nativeGrainTexture.empty() ? NULL : nativeGrainTexture.data();
    bool is_1024_grain = nativeGrainTexture.size() > 1000000;

    if (cinfo_d.output_height > 0) {
        row_pointer[0] = rows[10]; 
        jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
        for (int i = 0; i < 10; i++) memcpy(rows[i], rows[10], row_stride);
    }
    
    for (int i = 11; i < BUFFER_SIZE; i++) {
        if (cinfo_d.output_scanline < cinfo_d.output_height) {
            row_pointer[0] = rows[i];
            jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
        } else {
            memcpy(rows[i], rows[i-1], row_stride);
        }
    }

    int processed_rows = 0;
    while (processed_rows < (int)cinfo_d.output_height) {
        int rows_to_process = std::min(CHUNK_SIZE, (int)cinfo_d.output_height - processed_rows);
        pthread_t threads[16]; 
        ThreadTask tasks[16];
        int active_threads = 0;
        int threads_to_spawn = std::min(numCores, 16);
        
        for (int core = 0; core < threads_to_spawn; core++) {
            int start_i = core * rows_to_process / threads_to_spawn;
            int end_i = (core + 1) * rows_to_process / threads_to_spawn;
            if (start_i >= end_i) continue;

            tasks[active_threads].func = [&, start_i, end_i, active_threads]() {
                ThreadWorkspace& ws = workspaces[active_threads];
                for (int i = start_i; i < end_i; i++) {
                    int abs_y = processed_rows + i;
                    bool is_visible = !applyCrop || (abs_y >= skip_top && abs_y < skip_top + final_height);
                    if (is_visible) {
                        unsigned char* window[21];
                        for (int w = 0; w < 21; w++) window[w] = rows[i + w];
                        memcpy(out_rows[i], window[10], row_stride);
                        if (bloom > 0 || halation > 0) {
                            apply_bloom_halation(window, out_rows[i], cinfo_d.output_width, abs_y, !use_rgb_path, bloom, halation, 
                                                 ws.work_0, ws.work_1, ws.work_2, ws.work_h, ws.h_line, scaleDenom);
                        }
                        int t_off_x = start_time % 1021; 
                        int t_off_y = (start_time / 13) % 1021;
                        if (use_rgb_path) {
                            process_row_rgb(out_rows[i], cinfo_d.output_width, abs_y, cx, cy_center, vig_coef,
                                shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, 0, vignette,
                                grain, grainSize, scaleDenom, opac_mapped, map, nativeLut.data(), nativeLutSize, lutMax, lutSize2,
                                externalTex, is_1024_grain, t_off_x, t_off_y);
                        } else {
                            process_row_yuv(out_rows[i], cinfo_d.output_width, abs_y, cx, cy_center, vig_coef,
                                shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, 0, vignette,
                                grain, grainSize, scaleDenom, rolloff_lut, externalTex, is_1024_grain, t_off_x, t_off_y);
                        }
                    }
                }
            };
            pthread_create(&threads[active_threads], NULL, thread_runner, &tasks[active_threads]);
            active_threads++;
        }

        for (int i = 0; i < active_threads; i++) pthread_join(threads[i], NULL);

        for (int i = 0; i < rows_to_process; i++) {
            int abs_y = processed_rows + i;
            if (!applyCrop || (abs_y >= skip_top && abs_y < skip_top + final_height)) {
                row_pointer[0] = out_rows[i];
                jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
            }
        }

        unsigned char* temp_ptrs[256];
        for (int i = 0; i < rows_to_process; i++) temp_ptrs[i] = rows[i];
        for (int i = 0; i < BUFFER_SIZE - rows_to_process; i++) rows[i] = rows[i + rows_to_process];
        for (int i = 0; i < rows_to_process; i++) rows[BUFFER_SIZE - rows_to_process + i] = temp_ptrs[i];
        
        for (int i = 0; i < rows_to_process; i++) {
            int dest_idx = BUFFER_SIZE - rows_to_process + i;
            if (cinfo_d.output_scanline < cinfo_d.output_height) {
                row_pointer[0] = rows[dest_idx];
                jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
            } else {
                memcpy(rows[dest_idx], rows[dest_idx - 1], row_stride);
            }
        }
        processed_rows += rows_to_process;
    }

    free(row_block);
    free(out_block);
    for (int i = 0; i < 16; i++) {
        free(workspaces[i].work_0); free(workspaces[i].work_1); free(workspaces[i].work_2);
        free(workspaces[i].work_h); free(workspaces[i].h_line);
    }
    jpeg_finish_compress(&cinfo_c);  jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
    fclose(infile); fclose(outfile);
    env->ReleaseStringUTFChars(inPath, in_file);
    env->ReleaseStringUTFChars(outPath, out_file);
    return JNI_TRUE;
}
