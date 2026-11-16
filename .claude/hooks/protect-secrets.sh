#!/bin/sh
# PreToolUse: .env 파일 읽기·수정 차단 (토큰 보호). 차단 시 exit 2.
path=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))')
case "$path" in
  *.env|*/.env.*) [ "${path##*/}" = ".env.example" ] && exit 0
    echo ".env는 직접 읽거나 수정하지 않는다. 필요한 값은 사용자에게 요청할 것." >&2; exit 2 ;;
esac
