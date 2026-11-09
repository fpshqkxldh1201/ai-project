#!/usr/bin/env bash
# MySQL 전체 백업(gzip). 비밀번호는 컨테이너 안 환경변수로만 쓰고 명령줄에 남기지 않는다.
# 사용: cd deploy && ./backup.sh      (자동: crontab -e → 0 4 * * * cd /경로/deploy && ./backup.sh)
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
out="backups/lumi_$(date +%Y%m%d_%H%M).sql.gz"
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --databases lumi_database' | gzip > "$out"
find backups -name 'lumi_*.sql.gz' -mtime +14 -delete
echo "백업 완료: $out ($(du -h "$out" | cut -f1)) — VM 밖(내 PC 등)에도 복사해 두세요"
