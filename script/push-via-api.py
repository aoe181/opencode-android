#!/usr/bin/env python3
"""通过 GitHub REST API（Git Data API）推送本地提交。

适用于 github.com 的 git 端点被网络阻断、但 api.github.com 可达的环境。

校验策略：
- blob / tree 强校验（内容完整性有保证，tree SHA 必须与本地一致）；
- commit SHA 尽力一致，但 GitHub API 会把作者/提交者时间标准化为 UTC，
  本地时区非 UTC 时远端 SHA 会与本地分叉（内容等价）。分叉用
  .git/opencode-api-push.map 记录 local->remote 映射，后续推送据此续链；
  网络恢复后 `git push --force` 一次即可让远端重新对齐本地真实历史。

用法: python3 script/push-via-api.py
"""
import base64
import json
import os
import subprocess
import urllib.error
import urllib.request

API = "https://api.github.com"


def run(*args: str, binary: bool = False):
    out = subprocess.run(args, capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode().strip()


def api_request(token: str, method: str, path: str, payload=None, retries: int = 4):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "push-via-api-script",
            "Content-Type": "application/json",
        },
    )
    last_err = None
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = resp.read().decode()
                return resp.status, json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            body = e.read().decode()
            if e.code == 404:
                return e.code, None
            raise SystemExit(f"API {method} {path} -> {e.code}\n{body}")
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as e:
            last_err = e
            if attempt < retries:
                wait = attempt * 3
                print(f"  (网络错误，{wait}s 后重试 {attempt}/{retries}: {e})")
                import time
                time.sleep(wait)
    raise SystemExit(f"API {method} {path} 重试 {retries} 次后仍失败: {last_err}")


class ShaMap:
    """local -> remote SHA 映射，处理 GitHub 时区标准化导致的分叉。"""

    def __init__(self):
        git_dir = run("git", "rev-parse", "--git-dir")
        self.path = os.path.join(git_dir, "opencode-api-push.map")
        self.m = {}
        if os.path.exists(self.path):
            for line in open(self.path):
                parts = line.split()
                if len(parts) == 2:
                    self.m[parts[0]] = parts[1]

    def get(self, local_sha):
        return self.m.get(local_sha)

    def find_local(self, remote_sha):
        """反向查找：远端 SHA 对应的本地 SHA。"""
        for local, remote in self.m.items():
            if remote == remote_sha:
                return local
        return None

    def add(self, local_sha, remote_sha):
        self.m[local_sha] = remote_sha
        with open(self.path, "a") as f:
            f.write(f"{local_sha} {remote_sha}\n")


def push_one(token: str, repo: str, sha: str, parent_remote: str | None, notice: list) -> str:
    message = run("git", "log", "-1", "--format=%B", sha)
    tree_local = run("git", "rev-parse", f"{sha}^{{tree}}")
    parents_local = run("git", "log", "-1", "--format=%P", sha).split() or []
    author = {
        "name": run("git", "log", "-1", "--format=%an", sha),
        "email": run("git", "log", "-1", "--format=%ae", sha),
        "date": run("git", "log", "-1", "--format=%aI", sha),
    }
    committer = {
        "name": run("git", "log", "-1", "--format=%cn", sha),
        "email": run("git", "log", "-1", "--format=%ce", sha),
        "date": run("git", "log", "-1", "--format=%cI", sha),
    }

    # 1) 上传树内所有 blob（内容寻址，SHA 必然与本地一致）
    entries = []
    for line in run("git", "ls-tree", "-r", sha).splitlines():
        mode, otype, osha, fpath = line.split(None, 3)
        if otype != "blob":
            raise SystemExit(f"暂不支持非 blob 对象: {line}")
        content = run("git", "cat-file", "blob", osha, binary=True)
        _s, blob = api_request(token, "POST", f"/repos/{repo}/git/blobs",
                               {"content": base64.b64encode(content).decode(),
                                "encoding": "base64"})
        if blob["sha"] != osha:
            raise SystemExit(f"blob SHA 不一致: {fpath}")
        entries.append({"path": fpath, "mode": mode, "type": "blob", "sha": osha})

    # 2) 建 tree，强校验
    _s, tree = api_request(token, "POST", f"/repos/{repo}/git/trees", {"tree": entries})
    if tree["sha"] != tree_local:
        raise SystemExit(f"tree SHA 不一致: 本地 {tree_local} 远端 {tree['sha']}")

    # 3) 建 commit。分叉重放时 parent 需改接远端链
    if parent_remote is not None:
        parents = [parent_remote] if parents_local else []
    else:
        parents = []
    _s, commit = api_request(token, "POST", f"/repos/{repo}/git/commits", {
        "message": message,
        "tree": tree["sha"],
        "parents": parents,
        "author": author,
        "committer": committer,
    })
    remote_sha = commit["sha"]
    if remote_sha != sha:
        notice.append(sha)
        print(f"  (SHA 分叉: 本地 {sha[:12]} -> 远端 {remote_sha[:12]}，时区标准化所致)")
    print(f"pushed  {remote_sha[:12]}  {message.splitlines()[0][:60]}")
    return remote_sha


def main() -> None:
    token = run("gh", "auth", "token")
    remote = run("git", "remote", "get-url", "origin")
    repo = remote.split("github.com", 1)[1].lstrip("/:").removesuffix(".git")
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    shas = ShaMap()

    _s, ref = api_request(token, "GET", f"/repos/{repo}/git/ref/heads/{branch}")
    remote_head = ref["object"]["sha"] if ref and ref.get("object") else None

    # 定位本地基准：远端 head 本地若不存在，用映射表换算
    base_local = remote_head
    if remote_head is not None:
        check = subprocess.run(["git", "cat-file", "-e", f"{remote_head}^{{commit}}"],
                               capture_output=True)
        if check.returncode != 0:
            base_local = shas.find_local(remote_head)
            if base_local is None:
                raise SystemExit(
                    f"远端 head {remote_head} 本地不存在且无映射记录，"
                    "请先 git fetch 对齐或核对映射文件"
                )

    pending = (run("git", "rev-list", "--reverse", "HEAD").splitlines()
               if base_local is None
               else run("git", "rev-list", "--reverse", f"{base_local}..HEAD").splitlines())

    if not pending:
        print("nothing to push")
        return

    notice: list = []
    parent = remote_head
    for sha in pending:
        parent = push_one(token, repo, sha, parent, notice)
        shas.add(sha, parent)

    if remote_head is None:
        api_request(token, "POST", f"/repos/{repo}/git/refs",
                    {"ref": f"refs/heads/{branch}", "sha": parent})
    else:
        api_request(token, "PATCH", f"/repos/{repo}/git/refs/heads/{branch}",
                    {"sha": parent, "force": False})

    print(f"pushed {len(pending)} commit(s) -> https://github.com/{repo} ({branch})")
    if notice:
        print("注意：远端提交因时区标准化与本地 SHA 不同（内容一致）。"
              "网络恢复后执行 `git push --force` 可让远端重新对齐本地历史。")


if __name__ == "__main__":
    main()
