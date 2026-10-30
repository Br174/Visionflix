package com.streamflixreborn.streamflix.providers

/** Academy primary-category film winners 1929-2026.
 * Historical Academy-derived source (through 2024):
 * https://github.com/mauraanish/Oscars-Data/blob/main/oscars-data.csv
 * 2025/2026: https://www.oscars.org/oscars/ceremonies/
 * This is NOT film streaming availability evidence.
 */
internal object Lab190OscarPrimaryWinners {
    private val awards = """1929|The Last Command
1929|The Way of All Flesh
1929|7th Heaven
1929|Street Angel
1929|Sunrise
1929|Wings
1929|Two Arabian Knights
1930|In Old Arizona
1930|Coquette
1930|The Broadway Melody
1930|The Divine Lady
1931|Disraeli
1931|The Divorcee
1931|All Quiet on the Western Front
1932|A Free Soul
1932|Min and Bill
1932|Cimarron
1932|Skippy
1933|Dr. Jekyll and Mr. Hyde
1933|The Champ
1933|The Sin of Madelon Claudet
1933|Grand Hotel
1933|Bad Girl
1934|The Private Life of Henry VIII
1934|Morning Glory
1934|Cavalcade
1935|It Happened One Night
1936|The Informer
1936|Dangerous
1936|Mutiny on the Bounty
1937|The Story of Louis Pasteur
1937|The Great Ziegfeld
1937|Mr. Deeds Goes to Town
1938|Captains Courageous
1938|The Good Earth
1938|The Life of Emile Zola
1938|The Awful Truth
1939|Boys Town
1939|Jezebel
1939|You Can't Take It with You
1940|Goodbye, Mr. Chips
1940|Gone with the Wind
1941|The Philadelphia Story
1941|Kitty Foyle
1941|Rebecca
1941|The Grapes of Wrath
1942|Sergeant York
1942|Suspicion
1942|How Green Was My Valley
1943|Yankee Doodle Dandy
1943|Mrs. Miniver
1944|Watch on the Rhine
1944|The Song of Bernadette
1944|Casablanca
1945|Going My Way
1945|Gaslight
1946|The Lost Weekend
1946|Mildred Pierce
1947|The Best Years of Our Lives
1947|To Each His Own
1948|A Double Life
1948|The Farmer's Daughter
1948|Gentleman's Agreement
1948|Shoe-Shine
1949|Hamlet
1949|Johnny Belinda
1949|The Treasure of the Sierra Madre
1949|Monsieur Vincent
1950|All the King's Men
1950|The Heiress
1950|A Letter to Three Wives
1950|The Bicycle Thief
1951|Cyrano de Bergerac
1951|Born Yesterday
1951|All about Eve
1951|The Walls of Malapaga
1952|The African Queen
1952|A Streetcar Named Desire
1952|An American in Paris
1952|A Place in the Sun
1952|Rashomon
1953|High Noon
1953|Come Back, Little Sheba
1953|The Greatest Show on Earth
1953|The Quiet Man
1953|Forbidden Games
1954|Stalag 17
1954|Roman Holiday
1954|From Here to Eternity
1955|On the Waterfront
1955|The Country Girl
1955|Gate of Hell
1956|Marty
1956|The Rose Tattoo
1956|Samurai, The Legend of Musashi
1957|The King and I
1957|Anastasia
1957|Around the World in 80 Days
1957|Giant
1957|La Strada
1958|The Bridge on the River Kwai
1958|The Three Faces of Eve
1958|The Nights of Cabiria
1959|Separate Tables
1959|I Want to Live!
1959|Gigi
1959|My Uncle
1960|Ben-Hur
1960|Room at the Top
1960|Black Orpheus
1961|Elmer Gantry
1961|Butterfield 8
1961|The Apartment
1961|The Virgin Spring
1962|Judgment at Nuremberg
1962|Two Women
1962|West Side Story
1962|Through a Glass Darkly
1963|To Kill a Mockingbird
1963|The Miracle Worker
1963|Lawrence of Arabia
1963|Sundays and Cybele
1964|Lilies of the Field
1964|Hud
1964|Tom Jones
1964|Federico Fellini's 8-1/2
1965|My Fair Lady
1965|Mary Poppins
1965|Yesterday, Today and Tomorrow
1966|Cat Ballou
1966|Darling
1966|The Sound of Music
1966|The Shop on Main Street
1967|A Man for All Seasons
1967|Who's Afraid of Virginia Woolf?
1967|A Man and a Woman
1968|In the Heat of the Night
1968|Guess Who's Coming to Dinner
1968|The Graduate
1968|Closely Watched Trains
1969|Charly
1969|Funny Girl
1969|The Lion in Winter
1969|Oliver!
1969|War and Peace
1970|True Grit
1970|The Prime of Miss Jean Brodie
1970|Midnight Cowboy
1970|Z
1971|Patton
1971|Women in Love
1971|Investigation of a Citizen above Suspicion
1972|The French Connection
1972|Klute
1972|The Garden of the Finzi Continis
1973|The Godfather
1973|Cabaret
1973|The Discreet Charm of the Bourgeoisie
1974|Save the Tiger
1974|A Touch of Class
1974|The Sting
1974|Day for Night
1975|Harry and Tonto
1975|Alice Doesn't Live Here Anymore
1975|The Godfather Part II
1975|Amarcord
1976|One Flew over the Cuckoo's Nest
1976|Dersu Uzala
1977|Network
1977|Rocky
1977|Black and White in Color
1978|The Goodbye Girl
1978|Annie Hall
1978|Madame Rosa
1979|Coming Home
1979|The Deer Hunter
1979|Get Out Your Handkerchiefs
1980|Kramer vs. Kramer
1980|Norma Rae
1980|The Tin Drum
1981|Raging Bull
1981|Coal Miner's Daughter
1981|Ordinary People
1981|Moscow Does Not Believe in Tears
1982|On Golden Pond
1982|Chariots of Fire
1982|Reds
1982|Mephisto
1983|Gandhi
1983|Sophie's Choice
1983|Volver a Empezar ("To Begin Again")
1984|Tender Mercies
1984|Terms of Endearment
1984|Fanny & Alexander
1985|Amadeus
1985|Places in the Heart
1985|Dangerous Moves
1986|Kiss of the Spider Woman
1986|The Trip to Bountiful
1986|Out of Africa
1986|The Official Story
1987|The Color of Money
1987|Children of a Lesser God
1987|Platoon
1987|The Assault
1988|Wall Street
1988|Moonstruck
1988|The Last Emperor
1988|Babette's Feast
1989|Rain Man
1989|The Accused
1989|Pelle the Conqueror
1990|My Left Foot
1990|Driving Miss Daisy
1990|Born on the Fourth of July
1990|Cinema Paradiso
1991|Reversal of Fortune
1991|Misery
1991|Dances With Wolves
1991|Journey of Hope
1992|The Silence of the Lambs
1992|Mediterraneo
1993|Scent of a Woman
1993|Howards End
1993|Unforgiven
1993|Indochine
1994|Philadelphia
1994|The Piano
1994|Schindler's List
1994|Belle Epoque
1995|Forrest Gump
1995|Blue Sky
1995|Burnt by the Sun
1996|Leaving Las Vegas
1996|Dead Man Walking
1996|Braveheart
1996|Antonia's Line
1997|Shine
1997|Fargo
1997|The English Patient
1997|Kolya
1998|As Good as It Gets
1998|Titanic
1998|Character
1999|Life Is Beautiful
1999|Shakespeare in Love
1999|Saving Private Ryan
2000|American Beauty
2000|Boys Don't Cry
2000|All about My Mother
2001|Gladiator
2001|Erin Brockovich
2001|Traffic
2001|Crouching Tiger, Hidden Dragon
2002|Training Day
2002|Monster's Ball
2002|A Beautiful Mind
2002|No Man's Land
2003|The Pianist
2003|The Hours
2003|Chicago
2003|Nowhere in Africa
2004|Mystic River
2004|Monster
2004|The Lord of the Rings: The Return of the King
2004|The Barbarian Invasions
2005|Ray
2005|Million Dollar Baby
2005|The Sea Inside
2006|Capote
2006|Walk the Line
2006|Crash
2006|Brokeback Mountain
2006|Tsotsi
2007|The Last King of Scotland
2007|The Queen
2007|The Departed
2007|The Lives of Others
2008|There Will Be Blood
2008|La Vie en Rose
2008|No Country for Old Men
2008|The Counterfeiters
2009|Milk
2009|The Reader
2009|Slumdog Millionaire
2009|Departures
2010|Crazy Heart
2010|The Blind Side
2010|The Hurt Locker
2010|The Secret in Their Eyes
2011|The King's Speech
2011|Black Swan
2011|In a Better World
2012|The Artist
2012|The Iron Lady
2012|A Separation
2013|Lincoln
2013|Silver Linings Playbook
2013|Argo
2013|Life of Pi
2013|Amour
2014|Dallas Buyers Club
2014|Blue Jasmine
2014|12 Years a Slave
2014|Gravity
2014|The Great Beauty
2015|The Theory of Everything
2015|Still Alice
2015|Birdman or (The Unexpected Virtue of Ignorance)
2015|Ida
2016|The Revenant
2016|Room
2016|Spotlight
2016|Son of Saul
2017|Manchester by the Sea
2017|La La Land
2017|Moonlight
2017|The Salesman
2018|Darkest Hour
2018|Three Billboards outside Ebbing, Missouri
2018|The Shape of Water
2018|A Fantastic Woman
2019|Bohemian Rhapsody
2019|The Favourite
2019|Green Book
2019|Roma
2020|Joker
2020|Judy
2020|Parasite
2021|The Father
2021|Nomadland
2021|Another Round
2022|King Richard
2022|The Eyes of Tammy Faye
2022|CODA
2022|The Power of the Dog
2022|Drive My Car
2023|The Whale
2023|Everything Everywhere All at Once
2023|All Quiet on the Western Front
2024|Oppenheimer
2024|Poor Things
2024|The Zone of Interest
2025|Anora
2025|The Brutalist
2025|I'm Still Here
2026|One Battle after Another
2026|Sinners
2026|Hamnet
2026|Sentimental Value"""
    val entries: List<Pair<Int, String>> by lazy {
        awards.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('|')
            if (separator < 1) null else line.substring(0, separator).toIntOrNull()?.let {
                it to line.substring(separator + 1)
            }
        }.toList()
    }
}
