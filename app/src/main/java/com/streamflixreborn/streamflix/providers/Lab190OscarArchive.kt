package com.streamflixreborn.streamflix.providers

/**
 * LAB190 embedded zero-network award reference.
 * Oscar winners in five major categories through the 2023 ceremony,
 * keyed to TMDb movie IDs rather than fuzzy translated titles.
 *
 * Source: holmesredding/movieawardsdata, oscar-nominations.json (MIT license).
 * Based on Academy Awards records; 2024-2026 supplemental winners below
 * are confirmed on the Academy ceremony pages.
 * Copyright (c) original dataset author, adapted with attribution under MIT.
 *
 * This is historical metadata only: provider availability is checked separately.
 */
internal object Lab190OscarArchive {
    val principalAwardWinnerMovieIds: Set<Int> = setOf(
        13, 14, 33, 69, 70, 98, 99, 122, 142, 143, 146, 197, 223, 226, 238, 240, 
        274, 275, 279, 284, 288, 289, 322, 380, 396, 398, 403, 405, 409, 422, 423, 424, 
        427, 433, 451, 453, 462, 466, 488, 504, 510, 548, 581, 582, 590, 595, 596, 597, 
        606, 631, 632, 637, 654, 659, 665, 687, 702, 703, 705, 713, 746, 770, 783, 784, 
        789, 792, 804, 821, 826, 857, 868, 874, 880, 887, 947, 981, 1051, 1162, 1165, 1365, 
        1366, 1407, 1422, 1523, 1574, 1578, 1591, 1640, 1675, 1677, 1700, 1712, 1725, 1879, 1890, 1900, 
        1913, 1934, 1976, 2034, 2039, 2438, 2470, 2604, 2721, 2731, 2753, 2769, 2897, 2898, 3019, 3078, 
        3087, 3090, 3109, 3116, 3309, 4267, 4593, 4789, 5000, 5156, 5179, 5769, 5961, 6977, 7345, 7857, 
        7862, 7863, 8055, 8293, 8342, 9277, 9443, 9475, 9764, 9800, 10139, 10161, 10633, 10673, 10774, 10784, 
        10868, 11042, 11050, 11113, 11174, 11202, 11216, 11426, 11462, 11602, 11656, 11694, 11703, 11778, 11816, 11832, 
        11873, 11911, 12102, 12162, 12311, 12405, 13528, 13681, 14675, 14741, 15121, 15764, 15919, 16085, 16153, 16442, 
        16515, 16520, 16619, 16769, 16804, 17139, 17281, 17529, 17661, 17917, 18254, 18988, 19426, 21028, 22013, 22881, 
        23383, 23724, 24134, 24167, 24481, 24748, 24807, 25196, 25376, 25430, 25673, 25905, 26451, 27191, 27367, 28285, 
        28391, 28571, 28577, 28580, 28966, 29146, 29263, 29266, 31372, 31657, 31777, 33667, 33680, 34106, 36652, 37247, 
        38171, 38251, 38714, 38718, 38805, 40423, 40842, 41206, 41834, 42121, 42223, 42238, 42390, 42448, 42458, 42726, 
        42801, 42816, 42852, 42861, 43001, 43103, 43122, 43136, 43266, 43277, 43278, 43349, 43386, 43460, 43466, 43469, 
        43516, 43896, 44214, 44716, 45269, 45578, 46088, 47908, 49046, 49047, 50600, 50797, 52679, 52863, 54568, 56164, 
        57834, 60243, 62000, 65203, 65550, 65718, 66027, 68440, 68734, 71688, 72976, 74469, 74643, 76203, 77744, 80708, 
        81346, 82474, 82693, 83989, 84214, 84274, 84295, 84299, 85499, 85504, 85505, 85507, 86837, 87827, 102384, 104214, 
        152532, 160588, 179144, 194662, 209274, 264644, 266856, 281957, 284293, 313369, 314365, 334541, 336050, 359940, 375262, 375315, 
        376867, 399055, 399404, 424694, 426426, 429191, 467244, 475557, 490132, 491283, 496243, 545611, 580175, 581734, 600354, 600583, 
        601470, 614917, 758866, 776503, 785084, 792307, 872585
    )

    // Academy ceremonies 2024-2026, release years 2023, 2024 and 2025.
    // The Academy's categories are Picture, Directing, Leading Actor/Actress,
    // and International Feature. Keep release year to avoid remakes/homonyms.
    val recentPrincipalWinners: Map<Int, Set<String>> = mapOf(
        2023 to setOf("Oppenheimer", "Poor Things", "The Zone of Interest"),
        2024 to setOf("Anora", "The Brutalist", "I'm Still Here"),
        2025 to setOf("One Battle after Another", "Sinners", "Hamnet", "Sentimental Value")
    )

    // Verified 2026 Academy nominees across main acting/directing/best picture,
    // international, animation and major craft categories; release year 2025.
    val nominated2026: Set<String> = setOf(
        "Bugonia", "F1", "Frankenstein", "Hamnet", "Marty Supreme",
        "One Battle after Another", "The Secret Agent", "Sentimental Value",
        "Sinners", "Train Dreams", "Blue Moon", "If I Had Legs I'd Kick You",
        "Song Sung Blue", "Weapons", "KPop Demon Hunters", "Arco", "Elio",
        "Little Amélie or the Character of Rain", "Zootopia 2",
        "It Was Just an Accident", "Sirāt", "The Voice of Hind Rajab",
        "Mr. Nobody against Putin", "The Alabama Solution",
        "Come See Me in the Good Light", "Cutting through Rocks",
        "The Perfect Neighbor", "Avatar: Fire and Ash",
        "Jurassic World Rebirth", "The Lost Bus", "The Smashing Machine",
        "The Ugly Stepsister", "Kokuho", "Diane Warren: Relentless",
        "Viva Verdi!"
    )
}
