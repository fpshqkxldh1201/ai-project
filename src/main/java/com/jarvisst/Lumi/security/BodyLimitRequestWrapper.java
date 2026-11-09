package com.jarvisst.Lumi.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 실제로 읽은 요청 본문 바이트 수를 세어 한도를 넘으면 읽기를 중단한다.
 *
 * (정보보안개론 5장 코드 보안: 버퍼 오버플로는 "데이터 길이에 대한 불명확한 정의 + 경계 검사 없음"에서 시작한다.)
 * Content-Length 헤더만 검사하면 본문을 조각(chunked)으로 보내 헤더 없이 큰 데이터를 밀어 넣을 수 있다.
 * 헤더 값이 아니라 '실제로 읽은 양'으로 경계를 검사해서 그 우회를 막는다.
 */
public class BodyLimitRequestWrapper extends HttpServletRequestWrapper {

    /** 한도를 넘어 읽으려 할 때 던지는 예외. */
    public static class BodyTooLargeException extends IOException {
        public BodyTooLargeException(long limit) {
            super("요청 본문이 " + limit + "바이트를 넘었습니다.");
        }
    }

    private final long limit;
    private final Runnable onExceeded;
    private LimitedStream stream;

    public BodyLimitRequestWrapper(HttpServletRequest request, long limit, Runnable onExceeded) {
        super(request);
        this.limit = limit;
        this.onExceeded = onExceeded;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new LimitedStream(super.getInputStream(), limit, onExceeded);
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String enc = getCharacterEncoding();
        Charset charset = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private final Runnable onExceeded;
        private long count;
        private boolean reported;

        LimitedStream(ServletInputStream delegate, long limit, Runnable onExceeded) {
            this.delegate = delegate;
            this.limit = limit;
            this.onExceeded = onExceeded;
        }

        private void add(long n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > limit) {
                    if (onExceeded != null && !reported) {
                        reported = true;
                        onExceeded.run();
                    }
                    throw new BodyTooLargeException(limit);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            add(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
