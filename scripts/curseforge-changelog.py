"""Make a release changelog safe for CurseForge's HTML sanitiser.

CurseForge renders the changelog through its own sanitiser, and constructs it
cannot parse do not come back as a 400 naming the field -- they hang for about
thirty seconds and then return HTTP 500, "An unhandled exception occurred",
which reads like the service being down rather than like anything to do with
your text. It has cost two releases an hour each to work that out from scratch.

So the risky constructs are rewritten here rather than remembered by whoever is
cutting the release. Every rewrite is reported: silently editing somebody's
release notes would be a worse bug than the one this prevents.

Run standalone to check a file:  python3 scripts/curseforge-changelog.py notes.md
"""
import re, sys


def sanitise(text):
    """Returns (text, notes).

    Returns (text, notes). Every change is reported so the workflow log says
    what it did -- a silent rewrite of somebody's release notes would be worse
    than the bug it prevents.
    """
    notes = []

    # 1. Angle-bracket autolinks. Proven cause of a 500: the sanitiser reads
    #    <https://x> as a malformed tag. The bare URL still renders as a link.
    text, n = re.subn(r"<((?:https?|ftp)://[^>\s]+)>", r"\1", text)
    if n:
        notes.append(f"unwrapped {n} angle-bracket autolink(s)")

    lines = text.split("\n")
    out = []
    i = 0
    quoted = 0
    fenced = False
    code_runs = 0

    while i < len(lines):
        line = lines[i]

        # Leave fenced blocks entirely alone: they have survived every upload
        # so far, and rewriting somebody's code sample is not our business.
        if line.lstrip().startswith("```"):
            fenced = not fenced
            out.append(line)
            i += 1
            continue
        if fenced:
            out.append(line)
            i += 1
            continue

        # 2. Blockquotes. Suspected half of the 2.2.0 failure. The marker goes,
        #    the words stay -- a quote reads fine as an ordinary paragraph.
        if re.match(r"^\s{0,3}>\s?", line):
            out.append(re.sub(r"^\s{0,3}>\s?", "", line))
            quoted += 1
            i += 1
            continue

        # 3. Indented code blocks. The other suspected half, and the one people
        #    reach for most. Only when the run genuinely is a code block: it has
        #    to follow a blank line, and the line before THAT must not be a list
        #    item, because four spaces under a bullet is a list continuation and
        #    rewriting it would wreck the list.
        if re.match(r"^ {4,}\S", line):
            prev_blank = i > 0 and lines[i - 1].strip() == ""
            before = next((lines[j] for j in range(i - 2, -1, -1)
                           if lines[j].strip() != ""), "")
            in_list = bool(re.match(r"^\s*([-*+]|\d+\.)\s", before))
            if prev_blank and not in_list:
                run = []
                while i < len(lines) and (re.match(r"^ {4,}", lines[i])
                                          or lines[i].strip() == ""):
                    run.append(lines[i])
                    i += 1
                while run and run[-1].strip() == "":
                    run.pop()
                    i -= 1
                for r in run:
                    stripped = r.strip()
                    out.append(f"`{stripped}`" if stripped else "")
                code_runs += 1
                continue

        out.append(line)
        i += 1

    if quoted:
        notes.append(f"flattened {quoted} blockquote line(s) to plain text")
    if code_runs:
        notes.append(f"converted {code_runs} indented code block(s) to inline code")
    return "\n".join(out), notes


if __name__ == "__main__":
    text = open(sys.argv[1], encoding="utf-8").read()
    result, notes = sanitise(text)
    for n in notes:
        print(f"note: {n}", file=sys.stderr)
    sys.stdout.write(result)
