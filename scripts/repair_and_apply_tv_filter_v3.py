from pathlib import Path
import runpy

PATCH = Path('scripts/apply_tv_filter_panel_v3.py')
TV = Path('app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt')

text = PATCH.read_text(encoding='utf-8')
old = "match_marker = '    private fun matchesYearFilter(date: String?, filters: AdvancedSearchFilters): Boolean {'"
new = "match_marker = '''    private fun matchesYearFilter(\\n        date: String?,\\n        filters: AdvancedSearchFilters,\\n    ): Boolean {'''"
if old not in text:
    raise SystemExit('Broken matchesYearFilter marker not found in patch script')
PATCH.write_text(text.replace(old, new, 1), encoding='utf-8')

runpy.run_path(str(PATCH), run_name='__main__')

tv = TV.read_text(encoding='utf-8')

# Actor and director debouncing must be independent.
old_job = '    private var actorSearchJob: Job? = null\n'
if old_job not in tv:
    raise SystemExit('actorSearchJob marker missing after patch')
tv = tv.replace(old_job, old_job + '    private var directorSearchJob: Job? = null\n', 1)

tv = tv.replace(
    '        actorSearchJob?.cancel()\n        voiceHelper?.stopRecognition()\n',
    '        actorSearchJob?.cancel()\n        directorSearchJob?.cancel()\n        voiceHelper?.stopRecognition()\n',
    1,
)

start = tv.find('    private fun setupDirectorTextSearch() {')
end = tv.find('    private fun addActorSuggestionsSurface', start)
if start < 0 or end < 0:
    raise SystemExit('director setup block missing after patch')
block = tv[start:end]
block = block.replace('actorSearchJob?.cancel()', 'directorSearchJob?.cancel()')
block = block.replace('actorSearchJob = viewLifecycleOwner.lifecycleScope.launch', 'directorSearchJob = viewLifecycleOwner.lifecycleScope.launch')
tv = tv[:start] + block + tv[end:]

# If Apply is pressed before the new adapter results arrive, keep the pending focus request.
old_empty = '''        if (appAdapter.itemCount <= 0) {
            focusFirstResultAfterSearch = false
            return
        }
        focusFirstResultAfterSearch = false
'''
new_empty = '''        if (appAdapter.itemCount <= 0) {
            return
        }
        focusFirstResultAfterSearch = false
'''
if old_empty not in tv:
    raise SystemExit('focusFirstPoster empty marker missing')
tv = tv.replace(old_empty, new_empty, 1)

# When the panel is expanded there must be no poster overlap; when collapsed only the tiny handle reserves space.
# updateGridTopPadding reads the real current header height after visibility changes.

TV.write_text(tv, encoding='utf-8')
print('Repaired marker; applied TV v3 patch; separated actor/director jobs; fixed deferred first-poster focus.')
