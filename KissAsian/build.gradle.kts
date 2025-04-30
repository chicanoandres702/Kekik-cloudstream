group = "com.demonstratorz" // Replace with your group ID
version = 5 // Integer version number

cloudstream {
    description = "Kissasian Provider"
    authors = listOf("Andrew (Demonstratorz)")
    iconUrl = "https://www.google.com/s2/favicons?domain=kisskh.co&sz=%size%"
    status = 1
}

dependencies {
    // https://mvnrepository.com/artifact/me.xdrop/fuzzywuzzy
    implementation("me.xdrop:fuzzywuzzy:1.4.0")
    implementation("org.mozilla:rhino:1.7.14") // Add the Rhino dependency
}
