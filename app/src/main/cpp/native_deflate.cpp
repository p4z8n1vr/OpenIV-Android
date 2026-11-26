#include <jni.h>
#include <zlib.h>

#include <algorithm>
#include <array>

namespace {

constexpr jsize bufferSize = 32 * 1024;

void throwIOException(JNIEnv* env, const char* message) {
    const jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

class RawInflater {
public:
    RawInflater() : initialized_(inflateInit2(&stream, -MAX_WBITS) == Z_OK) {}

    ~RawInflater() {
        if (initialized_) {
            inflateEnd(&stream);
        }
    }

    RawInflater(const RawInflater&) = delete;
    RawInflater& operator=(const RawInflater&) = delete;

    [[nodiscard]] bool initialized() const { return initialized_; }

    z_stream stream{};

private:
    bool initialized_;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_p4z8n1vr_openiv_nativeio_NativeDeflate_inflateNative(
        JNIEnv* env, jobject, jobject input, jobject output, jlong compressedSize, jlong outputLimit) {
    if (compressedSize < 0 || outputLimit < 0) {
        throwIOException(env, "Invalid raw DEFLATE size limits");
        return 0;
    }

    const jclass inputClass = env->GetObjectClass(input);
    if (inputClass == nullptr) {
        return 0;
    }
    const jmethodID read = env->GetMethodID(inputClass, "read", "([BII)I");
    if (read == nullptr) {
        return 0;
    }
    const jclass outputClass = env->GetObjectClass(output);
    if (outputClass == nullptr) {
        return 0;
    }
    const jmethodID write = env->GetMethodID(outputClass, "write", "([BII)V");
    if (write == nullptr) {
        return 0;
    }

    const jbyteArray javaInput = env->NewByteArray(bufferSize);
    if (javaInput == nullptr) {
        return 0;
    }
    const jbyteArray javaOutput = env->NewByteArray(bufferSize);
    if (javaOutput == nullptr) {
        return 0;
    }

    RawInflater inflater;
    if (!inflater.initialized()) {
        throwIOException(env, "Could not initialize raw DEFLATE decompression");
        return 0;
    }

    std::array<Bytef, bufferSize> source;
    std::array<Bytef, bufferSize> destination;
    z_stream& stream = inflater.stream;
    jlong remaining = compressedSize;
    jlong written = 0;

    while (true) {
        if (stream.avail_in == 0 && remaining > 0) {
            const auto requested = static_cast<jint>(std::min<jlong>(bufferSize, remaining));
            const jint count = env->CallIntMethod(input, read, javaInput, 0, requested);
            if (env->ExceptionCheck()) {
                return 0;
            }
            if (count < 0) {
                throwIOException(env, "Truncated raw DEFLATE payload");
                return 0;
            }
            if (count == 0 || count > requested) {
                throwIOException(env, "Input stream returned an invalid byte count");
                return 0;
            }

            env->GetByteArrayRegion(javaInput, 0, count, reinterpret_cast<jbyte*>(source.data()));
            if (env->ExceptionCheck()) {
                return 0;
            }
            remaining -= count;
            stream.next_in = source.data();
            stream.avail_in = static_cast<uInt>(count);
        }

        const uInt inputBefore = stream.avail_in;
        stream.next_out = destination.data();
        stream.avail_out = bufferSize;
        const int result = inflate(&stream, Z_NO_FLUSH);
        if (result != Z_OK && result != Z_STREAM_END && result != Z_BUF_ERROR) {
            throwIOException(env, "Invalid raw DEFLATE payload");
            return 0;
        }

        const auto count = static_cast<jint>(bufferSize - stream.avail_out);
        if (count > outputLimit - written) {
            throwIOException(env, "Decompressed payload exceeds its size limit");
            return 0;
        }
        if (count > 0) {
            env->SetByteArrayRegion(javaOutput, 0, count, reinterpret_cast<const jbyte*>(destination.data()));
            if (env->ExceptionCheck()) {
                return 0;
            }
            env->CallVoidMethod(output, write, javaOutput, 0, count);
            if (env->ExceptionCheck()) {
                return 0;
            }
            written += count;
        }

        if (result == Z_STREAM_END) {
            return written;
        }
        if (count == 0 && stream.avail_in == inputBefore) {
            throwIOException(env, "Incomplete raw DEFLATE payload");
            return 0;
        }
    }
}
