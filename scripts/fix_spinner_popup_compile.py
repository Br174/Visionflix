from pathlib import Path

path = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt")
text = path.read_text(encoding="utf-8")

required = [
    "R.layout.fragment_search_tv_safe",
    "Spinner(requireContext(), Spinner.MODE_DROPDOWN)",
    "private inner class DropdownSelector<T>",
    "val target = (searchHeaderOffset + dy).coerceIn(0, maxOffset)",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"Refusing compile fix: missing expected safe marker: {marker}")

old = "popupBackgroundDrawable = ColorDrawable(Color.rgb(48, 48, 48))"
new = "setPopupBackgroundDrawable(ColorDrawable(Color.rgb(48, 48, 48)))"
if old not in text:
    if new in text:
        print("Spinner popup compile fix already applied.")
        raise SystemExit(0)
    raise SystemExit("Refusing compile fix: expected popup line not found")

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Applied Spinner popup setter compile fix; no other source changed.")
