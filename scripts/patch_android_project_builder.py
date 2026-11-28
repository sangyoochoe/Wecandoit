from pathlib import Path
import shutil
import re

root = Path('craft-anything-android/app/src/main/java/ai/storyteller/craftanything')
main = root / 'MainActivity.kt'
s = main.read_text(encoding='utf-8')
shutil.copyfile('scripts/ProjectBuilder.kt', root / 'ProjectBuilder.kt')

# ProjectBuilder.kt embeds shell scripts in Kotlin raw strings. Escape shell
# dollar signs only in those two literals so Kotlin does not treat $VAR or
# $(...) as Kotlin string templates while preserving the generated shell.
builder = root / 'ProjectBuilder.kt'
builder_source = builder.read_text(encoding='utf-8')
def escape_shell_dollars(match):
    return match.group(1) + match.group(2).replace('$', "${'$'}") + match.group(3)
builder_source, escaped_literals = re.subn(
    r'((?:val script|val workflow) = """)(.*?)(""")',
    escape_shell_dollars,
    builder_source,
    flags=re.DOTALL,
)
if escaped_literals != 2:
    raise SystemExit(f"Expected 2 embedded shell-script literals, found {escaped_literals}")

# The existing creator already receives the selected reference name and
# analysis evidence. Present it as a general analysis-to-app workflow rather
# than a game-only generator, and keep the evidence/request roles explicit.
replacements = {
    '참고 게임의 진행 방식은 참고하되 캐릭터와 배경은 새롭게 만들고 모바일 세로 화면에 맞춰 주세요.':
        '선택한 앱의 분석 결과를 참고해 핵심 사용 흐름을 이해하기 쉽게 개선한 새로운 Android 앱을 만들어 주세요. 원본 코드와 이미지는 복사하지 마세요.',
    'Text("새 게임 만들기", style = MaterialTheme.typography.headlineSmall)':
        'Text("분석 결과로 앱 만들기", style = MaterialTheme.typography.headlineSmall)',
    '참고자료 분석과 새 기능 요구사항을 분리해 독립적인 Android 프로젝트를 만듭니다. 원본 APK는 실행하지 않습니다.':
        '분석한 APK의 구조와 기능을 참고해 새 앱을 만들거나 개선합니다. 분석 근거와 새로 원하는 기능을 구분하며 원본 APK는 실행하거나 수정하지 않습니다.',
    'Text("새 게임 이름")': 'Text("새 앱 이름")',
    'Text("③ 새 프로젝트에 원하는 기능")': 'Text("③ 새 앱에 원하는 기능 또는 변경할 내용")',
    'Text("새로 만들 기능·디자인 요구사항")': 'Text("예: 비슷한 앱 만들기, 다크 모드 추가, 로그인 개선, 디자인 현대화")',
    'Text("① 참고자료 (원본 분석 근거)"': 'Text("① 선택한 앱의 분석 결과"'
}
for old, new in replacements.items():
    if old not in builder_source:
        raise SystemExit("ProjectBuilder integration anchor missing: " + old)
    builder_source = builder_source.replace(old, new, 1)

builder.write_text(builder_source, encoding='utf-8')
