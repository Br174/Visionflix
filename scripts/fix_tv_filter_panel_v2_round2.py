from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

# Fix Calendar sorting compile error and make Regista truly restrict to Director credits.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchViewModel.kt"
t = p.read_text()

t = replace_once(t,
'''        val platformProviderIds = filters.platform.providerIds
        val movieSort = when (filters.sort) {''',
'''        val platformProviderIds = filters.platform.providerIds
        val directorDetailsDeferred = directorId?.toIntOrNull()?.let { personId ->
            async {
                TMDb3.People.details(
                    personId = personId,
                    appendToResponse = listOf(
                        TMDb3.Params.AppendToResponse.Person.MOVIE_CREDITS,
                        TMDb3.Params.AppendToResponse.Person.TV_CREDITS,
                    ),
                    language = language,
                )
            }
        }
        val movieSort = when (filters.sort) {''',
"director credits deferred")

t = replace_once(t,
'''            val needsDiscoverConstraint = directorId != null || platformProviderIds != null
''',
'''            val needsDiscoverConstraint = platformProviderIds != null
''',
"query discover constraint only platform")

t = t.replace('''                        directorId?.let { params["with_crew"] = it }
                        platformProviderIds?.let {''',
'''                        platformProviderIds?.let {''', 2)

t = replace_once(t,
'''            val actorCredits = actorCreditsDeferred?.await().orEmpty()
            val actorMovieIds = actorCredits.filterIsInstance<Movie>().mapTo(hashSetOf()) { it.id }
            val actorTvIds = actorCredits.filterIsInstance<TvShow>().mapTo(hashSetOf()) { it.id }
            val restrictToActor = actorId != null
            val constrainedMovieIds = movieConstraintDeferred?.await()
            val constrainedTvIds = tvConstraintDeferred?.await()
''',
'''            val actorCredits = actorCreditsDeferred?.await().orEmpty()
            val actorMovieIds = actorCredits.filterIsInstance<Movie>().mapTo(hashSetOf()) { it.id }
            val actorTvIds = actorCredits.filterIsInstance<TvShow>().mapTo(hashSetOf()) { it.id }
            val restrictToActor = actorId != null
            val directorDetails = directorDetailsDeferred?.await()
            val directorMovieIds = directorDetails?.movieCredits?.crew
                ?.filter { it.job.equals("Director", ignoreCase = true) }
                ?.mapTo(hashSetOf()) { it.id.toString() }
                .orEmpty()
            val directorTvIds = directorDetails?.tvCredits?.crew
                ?.filter { it.job.equals("Director", ignoreCase = true) }
                ?.mapTo(hashSetOf()) { it.id.toString() }
                .orEmpty()
            val restrictToDirector = directorId != null
            val constrainedMovieIds = movieConstraintDeferred?.await()
            val constrainedTvIds = tvConstraintDeferred?.await()
''',
"director ids query")

t = replace_once(t,
'''                        if (restrictToActor && multi.id.toString() !in actorMovieIds) return@mapNotNull null
                        if (constrainedMovieIds != null && multi.id.toString() !in constrainedMovieIds) return@mapNotNull null
''',
'''                        if (restrictToActor && multi.id.toString() !in actorMovieIds) return@mapNotNull null
                        if (restrictToDirector && multi.id.toString() !in directorMovieIds) return@mapNotNull null
                        if (constrainedMovieIds != null && multi.id.toString() !in constrainedMovieIds) return@mapNotNull null
''',
"movie director restriction")

t = replace_once(t,
'''                        if (restrictToActor && multi.id.toString() !in actorTvIds) return@mapNotNull null
                        if (constrainedTvIds != null && multi.id.toString() !in constrainedTvIds) return@mapNotNull null
''',
'''                        if (restrictToActor && multi.id.toString() !in actorTvIds) return@mapNotNull null
                        if (restrictToDirector && multi.id.toString() !in directorTvIds) return@mapNotNull null
                        if (constrainedTvIds != null && multi.id.toString() !in constrainedTvIds) return@mapNotNull null
''',
"tv director restriction")

# For blank-query Discover, keep with_crew as a prefilter but enforce actual Director job afterwards.
t = replace_once(t,
'''        val movieItems = movieDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
''',
'''        val blankDirectorDetails = directorDetailsDeferred?.await()
        val blankDirectorMovieIds = blankDirectorDetails?.movieCredits?.crew
            ?.filter { it.job.equals("Director", ignoreCase = true) }
            ?.mapTo(hashSetOf()) { it.id.toString() }
            .orEmpty()
        val blankDirectorTvIds = blankDirectorDetails?.tvCredits?.crew
            ?.filter { it.job.equals("Director", ignoreCase = true) }
            ?.mapTo(hashSetOf()) { it.id.toString() }
            .orEmpty()

        val movieItems = movieDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
            .filter { directorId == null || it.id.toString() in blankDirectorMovieIds }
''',
"blank director movie filter")

t = replace_once(t,
'''        val tvItems = tvDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
''',
'''        val tvItems = tvDeferred?.await().orEmpty()
            .asSequence()
            .filter { !excludeItalian || it.originalLanguage != "it" }
            .filter { directorId == null || it.id.toString() in blankDirectorTvIds }
''',
"blank director tv filter")

t = replace_once(t,
'''                    is Movie -> it.released.orEmpty()
                    is TvShow -> it.released.orEmpty()
                    else -> ""
''',
'''                    is Movie -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    is TvShow -> it.released?.timeInMillis ?: Long.MIN_VALUE
                    else -> Long.MIN_VALUE
''',
"calendar date sort")

p.write_text(t)

# Current Max/HBO Max catalogues may use either legacy HBO Max or Max provider ids.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/providers/AdvancedSearch.kt"
t = p.read_text()
t = replace_once(t,
'''        HBO_MAX("HBO Max", "1899"),''',
'''        HBO_MAX("HBO Max / Max", "384|1899"),''',
"HBO Max provider ids")
p.write_text(t)

# Applying a search must select position zero before transferring focus to the results grid.
p = ROOT / "app/src/main/java/com/streamflixreborn/streamflix/fragments/search/SearchTvFragment.kt"
t = p.read_text()
t = replace_once(t,
'''        vgvSearch.scrollToPosition(0)
        vgvSearch.post {
            if (vgvSearch.requestFocus()) pendingFocusFirstResult = false
        }''',
'''        vgvSearch.scrollToPosition(0)
        vgvSearch.setSelectedPosition(0)
        vgvSearch.post {
            if (vgvSearch.requestFocus()) pendingFocusFirstResult = false
        }''',
"first poster selected position")
p.write_text(t)

print("TV filter panel v2 round2 fixes applied")
