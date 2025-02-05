// use an integer for version numbers
version = 3


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    // description = "Kiss Asian"
     authors = listOf("Hexated")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=kisskh.co&sz=%size%"
}
dependencies {
    // https://mvnrepository.com/artifact/me.xdrop/fuzzywuzzy
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("org.mozilla:rhino:1.7.14") // Add the Rhino dependency
}
