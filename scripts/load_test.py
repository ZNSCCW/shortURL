"""
短链接服务压力测试脚本

用法：
  python scripts/load_test.py [选项]

选项：
  --base-url  URL    目标地址（默认 http://localhost:8080）
  --concurrent N     并发线程数（默认 50）
  --total NUM        总请求数（默认 1000）
  --duration SEC     持续压测秒数（与 --total 二选一）

示例：
  python scripts/load_test.py --concurrent 100 --total 5000
  python scripts/load_test.py --concurrent 50 --duration 30
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import random
import string
import sys
import time
import urllib.request
import urllib.error
from collections import defaultdict


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """禁止自动跟随 302 重定向，以便捕获真实的 302 状态码"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

    def http_error_302(self, req, fp, code, msg, headers):
        # 返回原始 302 响应而非跟随重定向
        return fp


# 构建一个不跟随重定向的 opener
opener = urllib.request.build_opener(NoRedirectHandler())


# ── 统计 ──
stats = {
    "total": 0,
    "success": 0,
    "redirect": 0,   # 302
    "bad_request": 0,  # 400
    "not_found": 0,    # 404
    "server_error": 0,  # 5xx
    "error": 0,         # 连接错误等
    "latencies": [],     # 所有请求延迟（秒）
    "status_codes": defaultdict(int),
}
stats_lock = None  # 线程安全锁


def random_url():
    """生成随机长链接"""
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=12))
    return f"https://example.com/page/{suffix}"


def make_request(url, timeout=10):
    """发送一次请求并记录延迟，不跟随重定向以捕获真实 302"""
    start = time.perf_counter()
    try:
        req = urllib.request.Request(url, method="GET")
        with opener.open(req, timeout=timeout) as resp:
            elapsed = time.perf_counter() - start
            return (resp.status, elapsed, None)
    except urllib.error.HTTPError as e:
        elapsed = time.perf_counter() - start
        return (e.code, elapsed, None)
    except Exception as e:
        elapsed = time.perf_counter() - start
        return (0, elapsed, str(e))


def worker(args):
    """
    单线程 worker：先创建短链接，再大量访问它
    每个线程有一个固定的短码池供循环访问
    """
    base_url = args["base_url"]
    total = args["total"]
    stats_lock_inner = args["lock"]

    # 预创建一些短链接（最多 10 个）
    codes = []
    for _ in range(min(10, total)):
        try:
            long_url = random_url()
            data = json.dumps({"url": long_url}).encode()
            req = urllib.request.Request(
                f"{base_url}/api/shorten",
                data=data,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                body = json.loads(resp.read())
                if body.get("shortCode"):
                    codes.append(body["shortCode"])
        except Exception:
            pass  # 如果短链接创建失败，跳过

    # 如果没有短链接可用，跳过
    if not codes:
        return

    # 用短链接做 GET 请求
    for i in range(total):
        code = random.choice(codes)
        url = f"{base_url}/s/{code}"
        status, latency, err = make_request(url)

        with stats_lock_inner:
            stats["total"] += 1
            stats["latencies"].append(latency)
            stats["status_codes"][status] += 1

            if status == 302:
                stats["redirect"] += 1
                stats["success"] += 1
            elif 200 <= status < 300:
                stats["success"] += 1
            elif status == 400:
                stats["bad_request"] += 1
            elif status == 404:
                stats["not_found"] += 1
            elif status >= 500:
                stats["server_error"] += 1
            else:
                stats["error"] += 1


def percentile(data, pct):
    """计算百分位延迟"""
    if not data:
        return 0
    sorted_data = sorted(data)
    idx = int(len(sorted_data) * pct / 100)
    return sorted_data[min(idx, len(sorted_data) - 1)]


def format_ms(seconds):
    """格式化毫秒"""
    return f"{seconds * 1000:.1f}ms"


def main():
    parser = argparse.ArgumentParser(description="短链接负载测试")
    parser.add_argument("--base-url", default="http://localhost:8080", help="目标地址")
    parser.add_argument("--concurrent", type=int, default=50, help="并发线程数")
    parser.add_argument("--total", type=int, default=1000, help="总请求数")
    parser.add_argument("--duration", type=int, default=0, help="持续秒数（0=只用total）")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    n_workers = args.concurrent
    total = args.total

    print(f"\n{'='*60}")
    print(f"  短链接负载测试")
    print(f"  目标: {base_url}")
    print(f"  线程: {n_workers}  |  总请求: {total}")
    print(f"{'='*60}\n")

    # 先检查服务是否可达
    try:
        urllib.request.urlopen(f"{base_url}/", timeout=5)
    except Exception as e:
        print(f"❌ 无法连接到 {base_url}，请确保服务已启动")
        print(f"   错误: {e}")
        sys.exit(1)

    print("✅ 服务可达，开始压测...\n")

    import threading
    global stats_lock
    stats_lock = threading.Lock()

    per_worker = max(1, total // n_workers)
    remainder = total - per_worker * n_workers

    start_time = time.perf_counter()

    with ThreadPoolExecutor(max_workers=n_workers) as executor:
        futures = []
        for i in range(n_workers):
            cnt = per_worker + (1 if i < remainder else 0)
            futures.append(
                executor.submit(
                    worker,
                    {
                        "base_url": base_url,
                        "total": cnt,
                        "lock": stats_lock,
                    },
                )
            )

        # 打印进度
        while any(not f.done() for f in futures):
            with stats_lock:
                done_now = stats["total"]
            elapsed = time.perf_counter() - start_time
            qps = done_now / elapsed if elapsed > 0 else 0
            pct = done_now / total * 100 if total > 0 else 0
            print(
                f"\r 进度: {done_now}/{total} ({pct:.0f}%) | QPS: {qps:.1f} | 已用: {elapsed:.0f}s  ",
                end="",
            )
            time.sleep(0.3)

    elapsed = time.perf_counter() - start_time
    qps = stats["total"] / elapsed if elapsed > 0 else 0

    # 等待所有线程完成
    for f in futures:
        f.result()

    # ── 输出报告 ──
    t = stats  # alias
    n = t["total"]
    print(f"\n\n{'='*60}")
    print(f"  📊 测试结果")
    print(f"{'='*60}")
    print(f"  总请求数  : {n}")
    print(f"  成功(302) : {t['redirect']} ({t['redirect'] / n * 100:.1f}%)" if n else "  成功(302) : 0")
    print(f"  4xx       : {t['bad_request'] + t['not_found']} ({t['bad_request'] + t['not_found'] / n * 100:.1f}%)" if n else "  4xx : 0")
    print(f"  5xx       : {t['server_error']} ({t['server_error'] / n * 100:.1f}%)" if n else "  5xx : 0")
    print(f"  连接错误  : {t['error']} ({t['error'] / n * 100:.1f}%)" if n else "  连接错误 : 0")
    print()
    print(f"  耗时      : {elapsed:.2f}s")
    print(f"  平均 QPS  : {qps:.1f}")
    print()
    latencies = t["latencies"]
    if latencies:
        print(f"  平均延迟  : {format_ms(sum(latencies) / len(latencies))}")
        print(f"  最小延迟  : {format_ms(min(latencies))}")
        print(f"  最大延迟  : {format_ms(max(latencies))}")
        print(f"  P50       : {format_ms(percentile(latencies, 50))}")
        print(f"  P90       : {format_ms(percentile(latencies, 90))}")
        print(f"  P95       : {format_ms(percentile(latencies, 95))}")
        print(f"  P99       : {format_ms(percentile(latencies, 99))}")
    print(f"{'='*60}\n")

    # 状态码分布
    print("  状态码分布:")
    for code, cnt in sorted(t["status_codes"].items()):
        print(f"    HTTP {code}: {cnt}")
    print()

    # 结论
    error_rate = (t["server_error"] + t["error"]) / n * 100 if n else 0
    if error_rate == 0 and t["server_error"] == 0:
        print("  ✅ 结果: 无错误，服务稳定")
    elif error_rate < 1:
        print(f"  🟡 结果: 错误率 {error_rate:.2f}%，可接受")
    else:
        print(f"  🔴 结果: 错误率 {error_rate:.2f}%，需要优化！")


if __name__ == "__main__":
    main()