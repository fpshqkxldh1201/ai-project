package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 취약한 패스워드 방지 (정보보안개론 4장 웹보안 — A2 인증 및 세션 관리 취약점: "admin/admin 같은 취약한 패스워드").
 *
 * 길이(8자 이상)만으로는 'password', '12345678', 'admin1234' 같은 흔한 비밀번호를 막지 못한다.
 * 가입 시 서버에서 다음을 거부한다(화면 검사는 우회할 수 있으므로 서버에서 한다).
 *  1) 자주 쓰이는 비밀번호 목록에 있는 값
 *  2) 아이디와 같거나 아이디를 포함하는 값
 *  3) 같은 글자 반복(aaaaaaaa), 연속 숫자/문자(12345678, abcdefgh)
 *  4) 숫자만 또는 영문만으로 된 짧은(16자 미만) 값 — 최소 두 종류 이상 섞도록
 *
 * 사회공학 장 추가: "손가락 움직임만으로 쉽게 유추할 수 있는 패스워드(예: qwer1234)는 어깨너머로 훔쳐보기(Peeking)에
 * 쉽게 노출된다." 키보드에서 이웃한 키를 그대로 치는 값(qwer1234, asdf!@#$, 1q2w3e4r)을 거부한다.
 */
@Component
public class PasswordPolicy {

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "p@ssword",
            "12345678", "123456789", "1234567890", "123123123", "11111111", "00000000", "87654321",
            "qwerty123", "qwertyui", "qwertyuiop", "1q2w3e4r", "1qaz2wsx", "asdf1234", "asdfghjk", "zxcvbnm1",
            "admin123", "admin1234", "administrator", "root1234", "test1234", "guest123", "user1234",
            "welcome1", "welcome123", "iloveyou", "letmein1", "abc12345", "abcd1234", "dragon12",
            "lumi1234", "lumilumi", "jarvis123", "gemini123", "a1234567", "aa123456", "zaq12wsx"
    );

    /** 문제가 있으면 사용자에게 보여줄 안내 문구를, 괜찮으면 null을 돌려준다. */
    public String validate(String username, String password) {
        if (password == null) {
            return "비밀번호를 입력해주세요.";
        }
        String lower = password.toLowerCase(Locale.ROOT);

        if (COMMON.contains(lower)) {
            return "너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해주세요.";
        }
        if (username != null && !username.isBlank()) {
            String u = username.toLowerCase(Locale.ROOT);
            if (lower.equals(u) || (u.length() >= 3 && lower.contains(u))) {
                return "비밀번호에 아이디를 포함할 수 없습니다.";
            }
        }
        if (isRepeated(lower) || isSequential(lower)) {
            return "같은 문자 반복이나 연속된 문자·숫자로만 된 비밀번호는 사용할 수 없습니다.";
        }
        if (isKeyboardWalk(lower)) {
            return "키보드에서 나란히 있는 키를 그대로 친 비밀번호(예: qwer1234)는 어깨너머로 보기만 해도 알아맞힐 수 있어 사용할 수 없습니다.";
        }
        if (password.length() < 16 && kinds(password) < 2) {
            return "비밀번호는 영문, 숫자, 특수문자 중 두 종류 이상을 섞어주세요. (16자 이상이면 한 종류도 가능)";
        }
        return null;
    }

    private static int kinds(String s) {
        boolean letter = false, digit = false, other = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            else if (Character.isDigit(c)) digit = true;
            else other = true;
        }
        return (letter ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
    }

    private static boolean isRepeated(String s) {
        return s.chars().distinct().count() == 1;
    }

    /** 12345678, 87654321, abcdefgh 처럼 한 칸씩 증가/감소만 하는 값. */
    private static boolean isSequential(String s) {
        if (s.length() < 4) {
            return false;
        }
        int step = s.charAt(1) - s.charAt(0);
        if (Math.abs(step) != 1) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) - s.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private static final String[] KEY_ROWS = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "!@#$%^&*()"};
    /** 이웃한 키를 교대로 치는 대표 패턴(1q2w3e4r 처럼 두 줄을 번갈아 오르내림). */
    private static final String[] ZIGZAG = {"1q2w3e4r5t", "1qaz2wsx3edc", "zaq12wsx", "qazwsxedc", "q1w2e3r4t5"};

    /** 키보드 한 줄에서 4칸 이상 연속된 구간이 비밀번호의 70% 이상을 차지하거나, 6칸 이상 이어지면 true. */
    static boolean isKeyboardWalk(String s) {
        for (String z : ZIGZAG) {
            if (s.contains(z.substring(0, Math.min(8, z.length())))) {
                return true;
            }
        }
        boolean[] covered = new boolean[s.length()];
        int longest = 0;
        for (String row : KEY_ROWS) {
            String rev = new StringBuilder(row).reverse().toString();
            for (String r : new String[]{row, rev}) {
                for (int i = 0; i < s.length(); i++) {
                    int len = 0;
                    while (i + len < s.length()) {
                        int at = r.indexOf(s.charAt(i + len));
                        // 직전 글자 바로 다음 칸이어야 연속으로 본다
                        if (len == 0 ? at < 0 : !(at >= 0 && r.indexOf(s.charAt(i + len - 1)) + 1 == at)) {
                            break;
                        }
                        len++;
                    }
                    if (len >= 4) {
                        longest = Math.max(longest, len);
                        for (int k = i; k < i + len; k++) {
                            covered[k] = true;
                        }
                    }
                }
            }
        }
        int count = 0;
        for (boolean c : covered) {
            if (c) {
                count++;
            }
        }
        return longest >= 6 || (s.length() > 0 && count >= s.length() * 0.7);
    }
}
