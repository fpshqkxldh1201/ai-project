"""JWT(HttpOnly 쿠키 + 재발급 토큰 회전)를 기존 로그인에 연결하는 패치. 여러 번 실행해도 안전.

사용법 (프로젝트 루트, src 폴더가 보이는 곳):
  python tools/patch_jwt.py --dry-run
  python tools/patch_jwt.py
먼저 zip 의 java 파일들(security/Jwt*, entity/RefreshToken, repository/RefreshTokenRepository, controller/JwtAuthController)을 같은 경로에 복사하세요.

하는 일
  SecurityConfig : JWT 필터 등록 · 폼/구글 로그인 성공 시 JWT 발급 · 로그아웃 시 토큰 폐기 · /api/auth/refresh 허용
  AuthController : React(5173)용 POST /api/auth/login 이 세션 대신 JWT 쿠키를 발급
"""
import argparse
from pathlib import Path

BASE = Path("src/main/java/com/jarvisst/Lumi")


def read(path):
    raw = path.read_bytes()
    for enc in ("utf-8", "cp949"):
        try:
            return enc, raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise SystemExit(f"인코딩을 알 수 없어요: {path}")


def patch(path, steps, dry):
    enc, text = read(path)
    out, bad = text, 0
    print(path.name)
    for name, marker, anchor, new in steps:
        if marker in out:
            print(f"  이미 적용됨: {name}")
        elif out.count(anchor) != 1:
            print(f"  !! 못 찾음(수동 적용 필요): {name}")
            bad += 1
        else:
            out = out.replace(anchor, new)
            print(f"  적용: {name}")
    if out != text and not dry:
        path.write_bytes(out.encode(enc))
    return bad


SEC = [
    ("import LogoutFilter", "import org.springframework.security.web.authentication.logout.LogoutFilter;",
     "import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;",
     "import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n"
     "import org.springframework.security.web.authentication.logout.LogoutFilter;"),
    ("필터 체인에 JWT 빈 주입", "JwtAuthFilter jwtAuthFilter",
     "public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository) throws Exception {",
     "public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,\n"
     "                                                   JwtAuthFilter jwtAuthFilter, JwtLogoutHandler jwtLogoutHandler,\n"
     "                                                   JwtFormLoginSuccessHandler jwtFormLoginSuccessHandler,\n"
     "                                                   JwtOAuth2SuccessHandler jwtOAuth2SuccessHandler) throws Exception {"),
    ("JWT 인증 필터", ".addFilterBefore(jwtAuthFilter, LogoutFilter.class)",
     "                .addFilterBefore(sessionGuardFilter, AuthorizationFilter.class)\n",
     "                .addFilterBefore(sessionGuardFilter, AuthorizationFilter.class)\n"
     "                // JWT 쿠키로 로그인 상태를 만든다(로그아웃 필터보다 앞이어야 로그아웃 때도 누구인지 안다).\n"
     "                .addFilterBefore(jwtAuthFilter, LogoutFilter.class)\n"),
    ("/api/auth/refresh 허용", "\"/api/auth/refresh\"",
     "\"/login\", \"/register\", \"/api/auth/register\", \"/api/auth/login\",",
     "\"/login\", \"/register\", \"/api/auth/register\", \"/api/auth/login\", \"/api/auth/refresh\","),
    ("폼 로그인 성공 → JWT", ".successHandler(jwtFormLoginSuccessHandler)",
     "                        .defaultSuccessUrl(\"/\", true)\n",
     "                        .successHandler(jwtFormLoginSuccessHandler)\n"),
    ("구글 로그인 성공 → JWT", ".successHandler(jwtOAuth2SuccessHandler)",
     ".successHandler(new OAuth2ReturnToSuccessHandler())",
     ".successHandler(jwtOAuth2SuccessHandler)"),
    ("로그아웃 시 토큰 폐기", ".addLogoutHandler(jwtLogoutHandler)",
     "                        .addLogoutHandler(logoutCleanupHandler)\n",
     "                        .addLogoutHandler(logoutCleanupHandler)\n"
     "                        .addLogoutHandler(jwtLogoutHandler)\n"),
]

AUTH = [
    ("JWT 서비스 주입", "JwtSessionService jwtSessions",
     "    private final PasswordPolicy passwordPolicy;\n",
     "    private final PasswordPolicy passwordPolicy;\n\n"
     "    /** JWT 쿠키 발급(세션 대신). 필드 주입이라 생성자는 그대로 둔다. */\n"
     "    @org.springframework.beans.factory.annotation.Autowired\n"
     "    private com.jarvisst.Lumi.security.JwtSessionService jwtSessions;\n"),
    ("React 로그인 → JWT 발급", "jwtSessions.startLogin(",
     "            securityContextRepository.saveContext(context, httpRequest, httpResponse);\n",
     "            // 세션 대신 JWT(HttpOnly 쿠키)로 로그인 상태를 만든다.\n"
     "            jwtSessions.startLogin(authResult, httpRequest, httpResponse);\n"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    for need in ("security/JwtSessionService.java", "security/JwtAuthFilter.java", "entity/RefreshToken.java"):
        if not (BASE / need).exists():
            raise SystemExit(f"{need} 가 없어요. 먼저 zip 의 java 파일을 프로젝트에 복사하세요.")
    sec, auth = BASE / "security/SecurityConfig.java", BASE / "controller/AuthController.java"
    if not sec.exists() or not auth.exists():
        raise SystemExit("SecurityConfig.java / AuthController.java 를 못 찾았어요. 프로젝트 루트에서 실행하세요.")
    bad = patch(sec, SEC, a.dry_run) + patch(auth, AUTH, a.dry_run)
    print("\n완료" if not bad else f"\n수동 확인 필요 {bad}건 — 위 '못 찾음' 항목을 적용방법.txt 대로 직접 넣어 주세요.")


main()
