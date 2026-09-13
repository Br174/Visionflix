from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/streamflixreborn/streamflix/adapters/AppAdapter.kt"
t = p.read_text()
old = '''            is PeopleViewHolder -> holder.bind(\n                items[adjustedPosition] as People,\n                onPeopleClickListener,\n            )'''
new = '''            is PeopleViewHolder -> {\n                val people = items[adjustedPosition] as People\n                holder.bind(\n                    people,\n                    onPeopleClickListener.takeIf { people.itemType == Type.PEOPLE_TV_ITEM },\n                )\n            }'''
if old not in t:
    raise SystemExit("PeopleViewHolder bind anchor not found")
p.write_text(t.replace(old, new, 1))
print("People selector callback scoped to TV cards")
