from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import release_beta as base


BUILD_TASK = "app:assembleIframeOnlyRelease"
VARIANT_NAME = "iframeOnly"
APK_OUTPUT_DIR = (
    base.ROOT / "app" / "build" / "outputs" / "apk" / "release" / "iframeOnly"
)
GRADLEW = "gradlew.bat" if os.name == "nt" else "./gradlew"


base.APK_DIR = APK_OUTPUT_DIR
base.DEFAULT_BETA_NOTICE = (
    "## This is an iframe-only beta version intended for testing only. Trailer playback "
    "uses the YouTube iframe path, and in-app trailer extraction is excluded from the build."
)
base.EXPECTED_ASSET_NAMES = [
    "app-arm64-v8a-release.apk",
    "app-armeabi-v7a-release.apk",
    "app-x86_64-release.apk",
    "app-x86-release.apk",
    "app-universal-release.apk",
]


def release_notes_path(version_name: str) -> Path:
    base.RELEASE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = version_name.replace("/", "-")
    return base.RELEASE_OUTPUT_DIR / f"release-notes-iframe-only-{safe_name}.md"


def append_job_summary(
    *,
    mode: str,
    version_name: str,
    release_tag: str,
    release_title: str,
    commit_message: str,
    version_code: int,
    previous_tag: str | None,
    notes_path: Path,
    assets: list[Path] | None,
) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return

    lines = [
        "## Iframe-Only Beta Release Preview",
        "",
        f"- Mode: `{mode}`",
        f"- Version: `{version_name}`",
        f"- Tag: `{release_tag}`",
        f"- Title: `{release_title}`",
        f"- Commit message: `{commit_message}`",
        f"- versionCode: `{version_code}`",
        f"- Previous tag: `{previous_tag or 'none'}`",
        f"- Notes file: `{notes_path.relative_to(base.ROOT)}`",
        "",
        "### Release Notes",
        "",
        notes_path.read_text(encoding="utf-8").strip(),
        "",
        "### Assets",
    ]

    if assets:
        lines.extend(f"- `{path.name}`" for path in assets)
    else:
        lines.extend(f"- `{name}`" for name in base.EXPECTED_ASSET_NAMES)

    lines.append("")
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def build_release() -> list[Path]:
    subprocess.run(
        [GRADLEW, BUILD_TASK],
        cwd=base.ROOT,
        check=True,
        text=True,
    )
    assets = sorted(
        base.APK_DIR.glob("*.apk"),
        key=lambda path: next(
            (order for token, order in base.ASSET_ORDER.items() if token in path.name),
            999,
        ),
    )
    if not assets:
        raise SystemExit(f"No APK assets found in {base.APK_DIR}")
    return assets


base.release_notes_path = release_notes_path
base.append_job_summary = append_job_summary
base.build_release = build_release


def main() -> int:
    args = base.parse_args()

    selected_modes = [args.dry_run, args.publish, args.draft]
    if sum(1 for enabled in selected_modes if enabled) > 1:
        raise SystemExit("Use only one of --dry-run, --draft, or --publish.")
    if args.custom_notes and args.custom_notes_file:
        raise SystemExit("Use either --custom-notes or --custom-notes-file, not both.")

    original_contents = base.read_build_file()
    current_version_name, current_version_code = base.parse_versions(original_contents)
    previous_tag = base.last_tag()

    if args.manual_release:
        if args.version:
            raise SystemExit("Do not provide version when --manual-release is set.")
        if args.version_code is not None:
            raise SystemExit(
                "--version-code is not supported when --manual-release is set."
            )
        if not args.release_tag:
            raise SystemExit("--release-tag is required when --manual-release is set.")
        if not args.release_title:
            raise SystemExit(
                "--release-title is required when --manual-release is set."
            )
        target_version_name = current_version_name
        next_version_code = current_version_code
        release_tag = args.release_tag
        release_title = args.release_title
        commit_message = "manual release: no version bump"
        notes_key = release_tag
    else:
        if not args.version:
            raise SystemExit("version is required unless --manual-release is set.")
        if not base.VERSION_PATTERN.match(args.version):
            raise SystemExit(f"Invalid version format: {args.version}")
        target_version_name = args.version
        next_version_code = (
            args.version_code
            if args.version_code is not None
            else current_version_code + 1
        )
        if next_version_code < 1:
            raise SystemExit("versionCode must be a positive integer.")
        release_tag = args.release_tag or target_version_name
        release_title = args.release_title or release_tag
        commit_message = args.commit_message or f"release(iframe-only): {release_tag}"
        notes_key = target_version_name

    custom_notes = args.custom_notes
    if args.custom_notes_file:
        custom_notes = Path(args.custom_notes_file).read_text(encoding="utf-8")
    notes = base.build_release_notes(
        previous_tag=previous_tag,
        max_items=args.max_items,
        downloader_code=args.downloader_code,
        extra_notes=args.extra_notes,
        extra_lines=args.extra_line,
        custom_notes=custom_notes,
    )
    notes_path = release_notes_path(notes_key)
    notes_path.write_text(notes, encoding="utf-8")

    print(f"Release variant: {VARIANT_NAME}")
    print(f"Gradle task: {BUILD_TASK}")
    print(f"APK output dir: {base.APK_DIR.relative_to(base.ROOT)}")
    print(f"Current versionName: {current_version_name}")
    print(f"Current versionCode: {current_version_code}")
    print(f"Target versionName: {target_version_name}")
    print(f"Release tag: {release_tag}")
    print(f"Release title: {release_title}")
    print(f"Commit message: {commit_message}")
    print(f"Target versionCode: {next_version_code}")
    print(f"Previous tag: {previous_tag or 'none'}")
    print(f"Release notes: {notes_path.relative_to(base.ROOT)}")
    print()
    print(notes.strip())
    print()

    mode = (
        "dry-run"
        if args.dry_run
        else "draft"
        if args.draft
        else "publish"
        if args.publish
        else "local-build"
    )

    if args.dry_run:
        print("Dry run only validates the iframe-only release configuration.")
        print("No APKs are built in dry-run mode.")
        print("Expected assets in output directory:")
        for asset_name in base.EXPECTED_ASSET_NAMES:
            print(f"- {base.APK_DIR.relative_to(base.ROOT) / asset_name}")
        append_job_summary(
            mode=mode,
            version_name=target_version_name,
            release_tag=release_tag,
            release_title=release_title,
            commit_message=commit_message,
            version_code=next_version_code,
            previous_tag=previous_tag,
            notes_path=notes_path,
            assets=None,
        )
        return 0

    if args.publish or args.draft:
        base.ensure_clean_worktree()
        base.ensure_version_available(release_tag)

    updated_contents = original_contents
    if not args.manual_release:
        updated_contents = base.updated_build_file(
            original_contents,
            version_name=target_version_name,
            version_code=next_version_code,
        )
        base.write_build_file(updated_contents)
        print(f"Updated {base.BUILD_FILE.relative_to(base.ROOT)}")

    assets: list[Path] = []
    try:
        assets = build_release()
        print("Built iframe-only release assets:")
        for asset in assets:
            print(f"- {asset.relative_to(base.ROOT)}")

        if args.publish or args.draft:
            branch_name = base.current_branch()
            if args.manual_release:
                base.tag_push(release_tag, release_title, branch_name)
            else:
                base.commit_tag_push(
                    release_tag, release_title, commit_message, branch_name
                )
            base.create_github_release(
                release_tag,
                release_title,
                notes_path,
                assets,
                draft=args.draft,
            )
            if args.draft:
                print(
                    f"Created draft iframe-only GitHub release {release_tag} "
                    f"({release_title}) from branch {branch_name}"
                )
            else:
                print(
                    f"Published iframe-only GitHub release {release_tag} "
                    f"({release_title}) from branch {branch_name}"
                )
    except Exception:
        if not (args.publish or args.draft):
            base.write_build_file(original_contents)
        raise

    append_job_summary(
        mode=mode,
        version_name=target_version_name,
        release_tag=release_tag,
        release_title=release_title,
        commit_message=commit_message,
        version_code=next_version_code,
        previous_tag=previous_tag,
        notes_path=notes_path,
        assets=assets,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            sys.stdout.write(exc.stdout)
        if exc.stderr:
            sys.stderr.write(exc.stderr)
        raise SystemExit(exc.returncode) from exc
