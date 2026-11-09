"""서버 음성 인식(STT, OpenAI)을 Lumi에 연결하는 패치 스크립트 (백엔드 + 8081 화면 + 5173 React).

사용법 (프로젝트 루트 = src 폴더와 lumi-frontend 폴더가 보이는 곳에서):
  python tools/patch_stt.py --dry-run
  python tools/patch_stt.py

먼저 zip의 새 파일들(SttService/SttController/stt-server.js/serverStt.js)을 같은 경로에 복사해 두세요.
여러 번 실행해도 안전합니다(이미 적용된 곳은 건너뜀).
  1) ApiRequestGuardFilter.java : /api/stt 에 한해 요청 본문 1MB까지 허용(나머지는 기존 64KB 유지)
  2) static/index.html          : 서버 인식 엔진 스크립트 연결 + 설정창 '음성 인식 엔진' 선택
  3) lumi-frontend/src/context/LumiContext.jsx : 엔진 선택 적용
  4) lumi-frontend/src/components/SettingsModal.jsx : 설정창 '음성 인식 엔진' 선택
"""
import argparse
import sys
from pathlib import Path


def read(path):
    raw = path.read_bytes()
    bom = raw.startswith(b"\xef\xbb\xbf")
    for enc in ("utf-8", "cp949"):
        try:
            return enc, bom, raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return None, bom, None


def patch(path, steps, dry):
    if not path.exists():
        print(f"{path.name}\n  !! 파일이 없어요: {path}")
        return 1
    enc, bom, text = read(path)
    if text is None:
        print(f"{path.name}\n  !! 인코딩을 알 수 없어 건드리지 않았어요(수동 적용 필요)")
        return 1
    if bom:
        text = text.lstrip("﻿")
    out, notes, bad = text, [], 0
    for name, marker, anchor, new in steps:
        if marker in out:
            notes.append(f"  이미 적용됨: {name}")
        elif out.count(anchor) != 1:
            notes.append(f"  !! 못 찾음(수동 적용 필요): {name}")
            bad += 1
        else:
            out = out.replace(anchor, new)
            notes.append(f"  적용: {name}")
    if out != text and not dry:
        data = out.encode(enc)
        if bom:
            data = b"\xef\xbb\xbf" + data
        path.write_bytes(data)
    print(path.name)
    print("\n".join(notes))
    return bad


# ── 1) 백엔드 입구 필터 ──
GUARD_CONST_OLD = "    private static final int MAX_TRACKED_IPS = 20_000;\n"
GUARD_CONST_NEW = (
    "    /** 서버 음성 인식(STT): 짧은 음성 파일 업로드만 예외로 1MB까지 받는다. */\n"
    "    private static final String STT_PATH = \"/api/stt\";\n"
    "    private static final long MAX_STT_BODY_BYTES = 1024L * 1024;\n"
    + GUARD_CONST_OLD
)
GUARD_MAX_OLD = "        final long maxBody = path.equals(request.getContextPath() + IMAGE_CHAT_PATH) ? MAX_IMAGE_BODY_BYTES : MAX_BODY_BYTES;\n"
GUARD_MAX_NEW = (
    "        final long maxBody = path.equals(request.getContextPath() + IMAGE_CHAT_PATH) ? MAX_IMAGE_BODY_BYTES\n"
    "                : path.equals(request.getContextPath() + STT_PATH) ? MAX_STT_BODY_BYTES : MAX_BODY_BYTES;\n"
)

# ── 2) 8081 index.html ──
HTML_HEAD_OLD = "</head>"
HTML_HEAD_NEW = '<script src="/js/stt-server.js"></script>\n</head>'

HTML_ROW_OLD = (
    '      <div class="settings-row">\n'
    '        <label for="alwaysListenToggle">상시 듣기 (버튼 없이 항상 음성 대화)</label>\n'
)
HTML_ROW_NEW = (
    '      <div class="settings-row">\n'
    '        <label for="sttEngineSelect">음성 인식 엔진</label>\n'
    '        <select id="sttEngineSelect">\n'
    '          <option value="browser">브라우저 내장 (기본, 무료)</option>\n'
    '          <option value="server">서버 인식 (OpenAI, 더 정확)</option>\n'
    '        </select>\n'
    '      </div>\n'
) + HTML_ROW_OLD

HTML_IMPL_OLD = (
    "    const SpeechRecognitionImpl = window.SpeechRecognition || window.webkitSpeechRecognition;\n"
    "    if (!SpeechRecognitionImpl) {\n"
)
HTML_IMPL_NEW = (
    "    const NativeSpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;\n"
    "    // 설정에서 '서버 인식'을 고르고 서버가 준비돼 있으면 OpenAI 전사 엔진, 아니면 브라우저 기본 엔진.\n"
    "    const SpeechRecognitionImpl = window.LumiServerStt ? window.LumiServerStt.pick(NativeSpeechRecognition) : NativeSpeechRecognition;\n"
    "    if (!SpeechRecognitionImpl) {\n"
)

HTML_WIRE_OLD = "  inputLangSelect.addEventListener('change', saveSettings);\n"
HTML_WIRE_NEW = (
    HTML_WIRE_OLD
    + "  // 음성 인식 엔진 선택(이 브라우저에만 저장). '서버 인식'은 서버에 OPENAI_API_KEY가 있어야 켜진다.\n"
    "  const sttEngineSelect = document.getElementById('sttEngineSelect');\n"
    "  if (sttEngineSelect && window.LumiServerStt) {\n"
    "    sttEngineSelect.value = window.LumiServerStt.getEngine();\n"
    "    sttEngineSelect.addEventListener('change', async () => {\n"
    "      window.LumiServerStt.setEngine(sttEngineSelect.value);\n"
    "      if (sttEngineSelect.value !== 'server') return;\n"
    "      const ok = await window.LumiServerStt.refreshStatus();\n"
    "      if (ok) showToast('success', 'STT', '서버 음성 인식(OpenAI)으로 바꿨어요.');\n"
    "      else showToast('error', 'STT', '서버 음성 인식을 쓸 수 없어요(서버의 OPENAI_API_KEY 확인). 브라우저 인식으로 계속 동작해요.');\n"
    "    });\n"
    "  }\n"
)

# ── 3) React LumiContext ──
CTX_IMPORT_ANCHOR = None  # import 구문은 파일 첫 줄 앞에 삽입(아래 별도 처리)
CTX_IMPL_OLD = "    const SpeechRecognitionImpl = window.SpeechRecognition || window.webkitSpeechRecognition\n"
CTX_IMPL_NEW = (
    "    const NativeSpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition\n"
    "    // 설정에서 '서버 인식'을 고르고 서버가 준비돼 있으면 OpenAI 전사 엔진, 아니면 브라우저 기본 엔진\n"
    "    const SpeechRecognitionImpl = serverStt.pick(NativeSpeechRecognition)\n"
)

CTX_FIRST_IMPORT = "import "
CTX_IMPORT_LINE = "import { serverStt } from '../lib/serverStt'\n"


def patch_context(path, dry):
    if not path.exists():
        print(f"{path.name}\n  !! 파일이 없어요: {path}")
        return 1
    enc, bom, text = read(path)
    if text is None:
        print(f"{path.name}\n  !! 인코딩을 알 수 없어 건드리지 않았어요")
        return 1
    if bom:
        text = text.lstrip("﻿")
    out, notes, bad = text, [], 0
    if "lib/serverStt" in out:
        notes.append("  이미 적용됨: serverStt import")
    else:
        idx = out.find("import ")
        if idx < 0:
            notes.append("  !! import 위치를 못 찾음(수동 적용 필요)")
            bad += 1
        else:
            out = out[:idx] + CTX_IMPORT_LINE + out[idx:]
            notes.append("  적용: serverStt import")
    if "serverStt.pick(" in out:
        notes.append("  이미 적용됨: 엔진 선택")
    elif out.count(CTX_IMPL_OLD) != 1:
        notes.append("  !! 못 찾음(수동 적용 필요): 엔진 선택")
        bad += 1
    else:
        out = out.replace(CTX_IMPL_OLD, CTX_IMPL_NEW)
        notes.append("  적용: 엔진 선택")
    if out != text and not dry:
        data = out.encode(enc)
        if bom:
            data = b"\xef\xbb\xbf" + data
        path.write_bytes(data)
    print(path.name)
    print("\n".join(notes))
    return bad


# ── 4) React SettingsModal ── (구조가 달라 대표 앵커만 쓰고, 못 찾으면 안내)
def patch_settings_modal(path, dry):
    if not path.exists():
        print(f"{path.name}\n  !! 파일이 없어요: {path}")
        return 1
    enc, bom, text = read(path)
    if text is None:
        print(f"{path.name}\n  !! 인코딩을 알 수 없어 건드리지 않았어요")
        return 1
    if bom:
        text = text.lstrip("﻿")
    if "SttEngineRow" in text:
        print(f"{path.name}\n  이미 적용됨: 음성 인식 엔진 선택")
        return 0
    anchor = None
    for marker in ("htmlFor=\"voiceGenderSelect\"", "상시 듣기"):
        i = text.find(marker)
        if i >= 0:
            # 그 항목을 감싼 줄(<div ...>)의 시작을 찾는다
            j = text.rfind("<div", 0, i)
            k = text.rfind("\n", 0, j) + 1
            anchor = (k, text[k:j])
            break
    if anchor is None:
        print(f"{path.name}\n  !! 설정 항목 위치를 못 찾음(수동 적용 필요: <SttEngineRow /> 를 설정 항목 사이에 넣으세요)")
        return 1
    k, indent = anchor
    row = (
        f"{indent}<SttEngineRow />\n"
    )
    out = text[:k] + row + text[k:]
    comp = (
        "\n// 음성 인식 엔진 선택(이 브라우저에만 저장). '서버 인식'은 서버에 OPENAI_API_KEY가 있어야 켜진다.\n"
        "function SttEngineRow() {\n"
        "  const [engine, setEngine] = useState(serverStt.getEngine())\n"
        "  const [note, setNote] = useState('')\n"
        "  async function onChange(e) {\n"
        "    const v = e.target.value\n"
        "    serverStt.setEngine(v)\n"
        "    setEngine(v)\n"
        "    if (v === 'server') {\n"
        "      const ok = await serverStt.refreshStatus()\n"
        "      setNote(ok ? '' : '서버 음성 인식을 쓸 수 없어요(서버의 OPENAI_API_KEY 확인). 브라우저 인식으로 계속 동작해요.')\n"
        "    } else {\n"
        "      setNote('')\n"
        "    }\n"
        "  }\n"
        "  return (\n"
        "    <div className=\"settings-row\">\n"
        "      <label htmlFor=\"sttEngineSelect\">음성 인식 엔진</label>\n"
        "      <select id=\"sttEngineSelect\" value={engine} onChange={onChange}>\n"
        "        <option value=\"browser\">브라우저 내장 (기본, 무료)</option>\n"
        "        <option value=\"server\">서버 인식 (OpenAI, 더 정확)</option>\n"
        "      </select>\n"
        "      {note && <small>{note}</small>}\n"
        "    </div>\n"
        "  )\n"
        "}\n"
    )
    # import: useState / serverStt
    if "serverStt" not in out:
        first = out.find("import ")
        out = out[:first] + "import { serverStt } from '../lib/serverStt'\n" + out[first:]
    if "useState" not in out.split("export default")[0] and "{ useState" not in out and ", useState" not in out:
        first = out.find("import ")
        out = out[:first] + "import { useState } from 'react'\n" + out[first:]
    # 컴포넌트는 첫 'export default' 또는 'function SettingsModal' 앞에 삽입
    pos = out.find("export default")
    fpos = out.find("function SettingsModal")
    cand = [p for p in (pos, fpos) if p >= 0]
    ins = min(cand) if cand else len(out)
    out = out[:ins] + comp.lstrip("\n") + "\n" + out[ins:]
    if not dry:
        data = out.encode(enc)
        if bom:
            data = b"\xef\xbb\xbf" + data
        path.write_bytes(data)
    print(f"{path.name}\n  적용: 음성 인식 엔진 선택")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    java = root / "src/main/java/com/jarvisst/Lumi"
    static = root / "src/main/resources/static"
    fe = root / "lumi-frontend/src"

    need = [
        java / "service/SttService.java",
        java / "controller/SttController.java",
        static / "js/stt-server.js",
        fe / "lib/serverStt.js",
    ]
    missing = [str(p.relative_to(root)) for p in need if not p.exists()]
    if missing:
        print("먼저 zip의 새 파일을 같은 경로에 복사하세요. 없는 파일:\n  " + "\n  ".join(missing))
        return 1

    bad = 0
    bad += patch(java / "security/ApiRequestGuardFilter.java", [
        ("STT 업로드 크기 예외(상수)", "STT_PATH", GUARD_CONST_OLD, GUARD_CONST_NEW),
        ("STT 업로드 크기 예외(적용)", "MAX_STT_BODY_BYTES : MAX_BODY_BYTES", GUARD_MAX_OLD, GUARD_MAX_NEW),
    ], a.dry_run)
    bad += patch(static / "index.html", [
        ("서버 인식 스크립트 연결", "stt-server.js", HTML_HEAD_OLD, HTML_HEAD_NEW),
        ("설정창 '음성 인식 엔진'", 'id="sttEngineSelect"', HTML_ROW_OLD, HTML_ROW_NEW),
        ("엔진 선택 적용", "LumiServerStt.pick(", HTML_IMPL_OLD, HTML_IMPL_NEW),
        ("엔진 선택 동작 연결", "sttEngineSelect.addEventListener", HTML_WIRE_OLD, HTML_WIRE_NEW),
    ], a.dry_run)
    bad += patch_context(fe / "context/LumiContext.jsx", a.dry_run)
    bad += patch_settings_modal(fe / "components/SettingsModal.jsx", a.dry_run)
    if bad:
        print(f"\n{bad}곳은 자동으로 못 끼웠어요. 그 항목을 알려 주세요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
