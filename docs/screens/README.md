# Screens

One page per screen: what it's for, what it shows, how it behaves, and where the code is. Rounds of UX suggestions
are in [docs/ux-history/](../ux-history/README.md), linked from each screen's "Design history".

| Screen | Shown when | Doc | State |
|---|---|---|---|
| Notebooks | On launch: the lectures, by date, with their notebooks; Record | [notebooks.md](notebooks.md) | In the app |
| Lecture, while recording | New lecture | [lecture.md](lecture.md) | In the app (recording and notes; no lines yet) |
| Lecture, after | Opening a recorded lecture: play, edit, speakers, notes | [lecture-review.md](lecture-review.md) | In the app (the refine pass to come) |
| Settings › Models | The gear on Notebooks: the lectures folder, audio, models per language, Hugging Face | [models.md](models.md) | In the app |

```
Notebooks ──► Lecture (recording) ──Stop──► Lecture (after) ◄── Notebooks
    │
    └──► Settings ──► Models
```
