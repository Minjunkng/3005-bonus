#!/usr/bin/env python3
"""Recreate the captioned five-minute demonstration from actual engine output.

Needs Pillow and FFmpeg with its flite filter. The voice is synthetic and
explicitly identified in the first slide; the oral check remains personal.
"""
from __future__ import annotations

import subprocess
import textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "DEMO.mp4"
WORK = ROOT / ".video"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
TITLE = ImageFont.truetype(FONT, 30)
SUB = ImageFont.truetype(FONT, 21)
CODE = ImageFont.truetype(FONT, 19)
SMALL = ImageFont.truetype(FONT, 17)


def run(args: list[str]) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    displayed = " ".join('"' + arg + '"' if ' ' in arg else arg for arg in args)
    return "$ " + displayed + "\n" + \
           result.stdout + (result.stderr if result.stderr else "")


def draw_wrapped(draw, x, y, text, font, fill, width=64, spacing=7, max_lines=20):
    lines = []
    for original in text.splitlines():
        if not original:
            lines.append("")
        else:
            lines.extend(textwrap.wrap(original, width=width, break_long_words=True,
                                       break_on_hyphens=False, replace_whitespace=False))
    for line in lines[:max_lines]:
        draw.text((x,y),line,font=font,fill=fill)
        y += font.size + spacing
    return y


def make_slide(index, title, terminal, bullets, plot=False):
    im=Image.new("RGB",(1280,720),"#101b2c")
    d=ImageDraw.Draw(im)
    d.rounded_rectangle((25,25,1255,695),radius=18,outline="#3e5777",width=2)
    d.text((55,51),"COMP 3005  /  RELATIONAL ALGEBRA ENGINE",font=SUB,fill="#78d9e4")
    d.text((55,93),title,font=TITLE,fill="#f8f7f3")
    d.rounded_rectangle((55,151,840,626),radius=13,fill="#07111e",outline="#324c62",width=2)
    d.text((77,170),"TERMINAL / MEASURED OUTPUT" if not plot else "MEASURED PLOT",
           font=SMALL,fill="#80c8e6")
    if plot:
        pic=Image.open(ROOT/"docs"/"performance_loglog.png").convert("RGB")
        pic.thumbnail((730,406))
        im.paste(pic,(78,204))
    else:
        draw_wrapped(d,78,210,terminal,CODE,"#e7f1eb",width=64,spacing=6,max_lines=18)
    d.rounded_rectangle((862,151,1225,626),radius=13,fill="#172840")
    d.text((889,173),"WHAT THIS SHOWS",font=SMALL,fill="#82d9e3")
    draw_wrapped(d,889,220,bullets,SUB,"#f0eee9",width=25,spacing=9,max_lines=15)
    d.text((55,651),f"Chapter {index+1} / 8    Synthetic narration; commands captured from this engine",
           font=SMALL,fill="#b6bfca")
    dest=WORK/f"slide_{index}.png"
    im.save(dest)
    return dest


def audio(text,index):
    narration=WORK/f"narration_{index}.txt"
    narration.write_text(text,encoding="utf-8")
    result=WORK/f"audio_{index}.wav"
    subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-y",
                    "-f","lavfi","-i",f"flite=textfile={narration}:voice=kal",
                    "-af","atempo=0.84","-ar","22050","-ac","1",str(result)],
                   check=True)
    return result


def main():
    subprocess.run(["make"],cwd=ROOT,check=True,capture_output=True)
    WORK.mkdir(exist_ok=True)
    tests=subprocess.run(["make","test"],cwd=ROOT,text=True,capture_output=True,check=True)
    test_output="$ make test\n"+"\n".join(tests.stderr.splitlines()[-5:])
    java=["sh","tools/java.sh","-cp","build","RA"]
    tree_set=run(java+["--tree","A union B minus C"])
    tree_unary=run(java+["--tree","project[Name](select[Age>30](Employees))"])
    projected=run(java+["--data","examples/employees.ra","--query",
                   "project[DID](Employees)"])
    joined=run(java+["--data","examples/employees.ra","--query",
                "rename[E2](Emp) join[Emp.MgrID=E2.EID] Emp","--count-only"])
    error=run(java+["--data","examples/employees.ra","--query","Emp times Emp"])
    single=run(java+["--tree","select[Name='O''Brien' and Age>=-30](Employees)"])
    source_sections=run(["rg","-n","class Scanner|class Parser|static Relation evaluate","src/RA.java"])
    slides=[
        ("The project builds and its tests run",test_output,
         "35 automated tests passed.\n\nThe first 25 match the assignment's numbered cases.\n\nThe engine uses Java 17 and a hand-written parser.",
         "This is a synthetic-voice demonstration of a working relational algebra engine. The command shown here ran the complete test suite: thirty-five tests passed, including each of the twenty-five cases specified by the assignment. The remaining tests exercise decimal ordering, missing and ambiguous attributes, unusual strings, set semantics, and the operator counters. The README shows how to compile with make and execute the same tests from a clean checkout."),
        ("The scanner handles awkward input",single,
         "The doubled quote is one character.\n\n>= is one token.\n\n-30 is one numeric literal.\n\nTokens carry positions for errors.",
         "The scanner reads one character at a time, retaining the starting line and column of each token. Here the doubled quote inside O'Brien is decoded as one quote, greater than or equal is scanned as one token, and minus thirty is one signed number after the comparison operator. Whitespace is optional between query tokens. A closing parenthesis or comma inside a quoted string stays inside the string. Unterminated strings report the opening quote position."),
        ("Precedence and associativity",tree_set,
         "Minus is the root.\n\nUnion is its left child.\n\nThis is (A union B) minus C.\n\nThe grammar forces this grouping.",
         "In the printed tree, Minus is the root and Union is its left child. That means A union B minus C groups as open parenthesis A union B close parenthesis minus C. The naive grammar in the assignment also permits A union open parenthesis B minus C close parenthesis, which can produce different rows. GRAMMAR dot M D shows both parse trees and a concrete counterexample. The precedence-stratified set expression rule parses equal-precedence operators with a left fold."),
        ("Nested expressions evaluate inside out",tree_unary,
         "No relation file is needed for --tree.\n\nProject contains Select.\n\nSelect contains Employees.",
         "Tree mode builds the syntax tree without evaluating the query or loading a relation file. The project node contains a select node, which contains the Employees relation reference. When running the query, evaluation visits the input first and then applies the enclosing operator. The parser uses a separate function for each precedence level. Parentheses around each unary input are mandatory, so a missing closing parenthesis produces a syntax error with its position."),
        ("Projection uses set semantics",projected,
         "Employees has three rows.\n\nDID has two distinct values.\n\nProject removes duplicate tuples.",
         "Employees has three rows, but two employees share department D one. Projection keeps the requested DID column and removes duplicate result rows, leaving two distinct departments. The implementation defines tuple equality explicitly, with numeric values compared numerically and strings compared as strings. A hash table only narrows candidate rows; tuple equality decides whether a duplicate exists. Selection can compare two attributes as well as an attribute and a literal."),
        ("Rename enables a self join",joined+"\n"+error,
         "E2 and Emp identify separate copies.\n\nBoth EID columns survive.\n\nEmp times Emp reports a schema error.",
         "The rename operation gives the left copy of Emp the qualifier E two, and the unrenamed right copy keeps qualifier Emp. The join condition can then refer to Emp manager I D and E two employee I D independently. Both columns remain in the join output; this is a theta join, not a natural join. Without rename, Emp times Emp creates duplicate fully qualified attribute names, so the engine reports a controlled schema error."),
        ("Measured behavior on seven sizes","",
         "64,000 × 64,000 rows.\n\n4,096,000,000 comparisons.\n\n6.730319568 seconds (median).\n\nLarge-size slope ≈ 2.02.",
         "The performance plot shows Java evaluation times on logarithmic axes. At sixty-four thousand rows per relation, the engine counted four billion ninety-six million pair comparisons and had a median time of six point seven three seconds over three timed runs in this optimized integer equality loop. Every measured count equals n times m exactly. The seven-size fitted slope is one point six eight because Java warmup and fixed work affect shorter runs. For the largest three sizes the slope is about two point zero two, matching the quadratic nested loops. Selection and projection grow much more slowly."),
        ("Code structure and cost prediction",
         source_sections,
         "1,000,000² = 1 trillion pairs.\n\nPrediction ≈ 1,643 seconds.\n\nA hash join is future work.\n\nReview the code before oral check.",
         "The Java source is divided into scanning, relation definitions, parsing and tree printing, typed predicate binding, operator evaluation, and the command line. The join counter increments inside the loop that visits every pair, while a select counter increments once per examined tuple. To estimate one million rows on both sides, multiply the measured sixty-four-thousand-row time by the squared size ratio, two hundred forty-four point one four. The result is about one thousand six hundred forty-three seconds, or twenty-seven point four minutes. I did not run that case. A hash join could reduce expected equality-join work in a future project.")
    ]
    segments=[]
    durations=[]
    for i,(title,terminal,bullets,narration) in enumerate(slides):
        picture=make_slide(i,title,terminal,bullets,plot=(i==6))
        sound=audio(narration,i)
        duration=subprocess.check_output(["ffprobe","-v","error","-show_entries",
                                          "format=duration","-of","default=noprint_wrappers=1:nokey=1",
                                          str(sound)],text=True).strip()
        durations.append(float(duration))
        segment=WORK/f"segment_{i}.mp4"
        subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-y",
                        "-loop","1","-framerate","6","-i",str(picture),
                        "-i",str(sound),"-c:v","libx264","-tune","stillimage",
                        "-pix_fmt","yuv420p","-r","6","-c:a","aac","-b:a","80k",
                        "-shortest",str(segment)],check=True)
        segments.append(segment)
        print(f"chapter {i+1}: {float(duration):.1f} seconds",flush=True)
    playlist=WORK/"segments.txt"
    playlist.write_text("".join(f"file '{segment.resolve()}'\n" for segment in segments),encoding="utf-8")
    subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-y",
                    "-f","concat","-safe","0","-i",str(playlist),"-c","copy",str(OUT)],check=True)
    final=subprocess.check_output(["ffprobe","-v","error","-show_entries",
                                   "format=duration","-of","default=noprint_wrappers=1:nokey=1",str(OUT)],
                                  text=True).strip()
    print(f"Video: {OUT} ({float(final):.1f} seconds; {sum(durations):.1f} seconds of narration)")


if __name__=="__main__":
    main()
