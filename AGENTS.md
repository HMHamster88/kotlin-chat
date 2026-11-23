# Agent instructions

## Releases

- **Create a new tag for a release only when the user explicitly asks for it.** Do not tag a
  release after implementing a feature, bumping `app.version`, committing, or pushing to
  `master`. Building, testing and pushing code are fine; `git tag` and `git push origin <tag>`
  wait for a clear request.

## Mockups and design previews

- **Do not add PNG mockup files to git.** Keep rendered icon mockups and other preview images in
  an ignored directory such as `build/icon-mockup/`. Commit source files (for example the SVG)
  only when the user asks for the design assets to be tracked.
