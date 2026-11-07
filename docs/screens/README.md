# Screens

One page per screen: what it's for, what it shows, how it behaves, and where the code is. Rounds of UX suggestions
are in [docs/ux-history/](../ux-history/README.md), linked from each screen's "Design history".

| Screen | Shown when | Doc | State |
|---|---|---|---|
| Notebooks | On launch: the moments, by date, with their labels; Record | [notebooks.md](notebooks.md) | In the app |
| Moment, while recording | New moment | [lecture.md](lecture.md) | In the app (recording and notes; no lines yet) |
| Moment, after | Opening a recorded moment: play, edit, speakers, notes | [lecture-review.md](lecture-review.md) | In the app (the refine pass to come) |
| Settings › Models | The gear on Notebooks: the moments folder, audio, models per language, Hugging Face | [models.md](models.md) | In the app |

```
Notebooks ──► Lecture (recording) ──Stop──► Lecture (after) ◄── Notebooks
    │
    └──► Settings ──► Models
```
