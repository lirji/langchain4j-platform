#!/bin/sh
set -eu

case "${SANDBOX_CWD:-.}" in
    /*|..|../*|*/../*|*/..)
        echo "invalid SANDBOX_CWD" >&2
        exit 64
        ;;
esac

mkdir -p /work/repo /work/home
cp -a /source/. /work/repo/
cd "/work/repo/${SANDBOX_CWD:-.}"
exec "$@"
