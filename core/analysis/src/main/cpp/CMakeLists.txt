cmake_minimum_required(VERSION 3.22.1)
project(nextplayer_fingerprint C CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)

set(CHROMAPRINT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/chromaprint)

add_library(nextplayer_fingerprint SHARED
    nextplayer_fingerprint.cpp
    ${CHROMAPRINT_DIR}/src/chromaprint.cpp
    ${CHROMAPRINT_DIR}/src/audio_processor.cpp
    ${CHROMAPRINT_DIR}/src/chroma.cpp
    ${CHROMAPRINT_DIR}/src/chroma_filter.cpp
    ${CHROMAPRINT_DIR}/src/chroma_resampler.cpp
    ${CHROMAPRINT_DIR}/src/fft.cpp
    ${CHROMAPRINT_DIR}/src/fft_lib_kissfft.cpp
    ${CHROMAPRINT_DIR}/src/fingerprint_calculator.cpp
    ${CHROMAPRINT_DIR}/src/fingerprint_compressor.cpp
    ${CHROMAPRINT_DIR}/src/fingerprint_decompressor.cpp
    ${CHROMAPRINT_DIR}/src/fingerprint_matcher.cpp
    ${CHROMAPRINT_DIR}/src/fingerprinter.cpp
    ${CHROMAPRINT_DIR}/src/fingerprinter_configuration.cpp
    ${CHROMAPRINT_DIR}/src/image_builder.cpp
    ${CHROMAPRINT_DIR}/src/silence_remover.cpp
    ${CHROMAPRINT_DIR}/src/spectrum.cpp
    ${CHROMAPRINT_DIR}/src/simhash.cpp
    ${CHROMAPRINT_DIR}/src/utils/base64.cpp
    ${CHROMAPRINT_DIR}/src/avresample/resample2.c
    ${CHROMAPRINT_DIR}/src/3rdparty/kissfft/kiss_fft.c
    ${CHROMAPRINT_DIR}/src/3rdparty/kissfft/kiss_fftr.c
)

target_include_directories(nextplayer_fingerprint PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CHROMAPRINT_DIR}/src
    ${CHROMAPRINT_DIR}/src/3rdparty/kissfft
)

target_compile_definitions(nextplayer_fingerprint PRIVATE
    USE_KISSFFT=1
    USE_INTERNAL_AVRESAMPLE=1
    HAVE_ROUND=1
    HAVE_LRINTF=1
)

target_compile_options(nextplayer_fingerprint PRIVATE
    -fexceptions
    -O2
)

# Support 16 KB memory pages (required for Android 15+ devices).
target_link_options(nextplayer_fingerprint PRIVATE
    "-Wl,-z,max-page-size=16384"
)
