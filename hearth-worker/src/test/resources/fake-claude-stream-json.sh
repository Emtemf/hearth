#!/usr/bin/env sh
set -eu

read -r first
printf '%s\n' '{"type":"system","subtype":"init"}'
printf '%s\n' '{"type":"assistant","message":{"content":"first"}}'
printf '%s\n' '{"type":"result","subtype":"success"}'

read -r second
printf '%s\n' '{"type":"assistant","message":{"content":"second"}}'
printf '%s\n' '{"type":"result","subtype":"success"}'
