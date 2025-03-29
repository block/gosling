package xyz.block.gosling.features.agent

/**
 * Store and retrieve per app instructions.
 */
object AppInstructions {
    /**
     * Map of package names to their corresponding instructions and URL schemes.
     * Each instruction is a multiline string providing guidance for the specific app.
     */
    private val appInstructionsMap = mapOf(
        // Maps apps
        "com.google.android.apps.maps" to AppInstructionInfo(
            instructions = """            
                First think if the user wants to:
                1. Find a specific location
                2. Get directions between locations
                3. Explore nearby places
                4. Add stops on the way
                IMPORTANT: don't just search for A to B, but search for separate places, add stops if needed.
                           Map search is not general search. There may be locations user is asking for mixed in with general place (like a vegan restaurant)

                URL Schemes:
                - Use 'geo:0,0?q=LOCATION' to search for a location
                - Use 'google.navigation:q=DESTINATION' to start navigation
                - Use 'google.streetview:cbll=LAT,LONG' to view street view at coordinates
                - Use 'google.streetview:panorama_id=ID' to view specific street view locations
            """.trimIndent(),
            urlSchemes = listOf(
                "geo:0,0?q=", // For searching locations
                "google.navigation:q=", // For navigation
                "google.streetview:cbll=", // For street view
                "google.streetview:panorama_id=" // For specific street view locations
            )
        ),

        "com.waze" to AppInstructionInfo(
            instructions = """
                Waze is a community-driven navigation app focused on real-time traffic updates.
                
                Key features:
                - GPS navigation with real-time traffic updates
                - Community-reported hazards, police, and road closures
                - Find the cheapest gas stations along your route
                - Estimated arrival times based on live traffic data
                - Integration with music apps and calendar appointments
                
                When using Waze, help the user:
                1. Enter a destination (use the search bar)
                2. Choose the best route based on current conditions
                3. Report or view road hazards and traffic conditions
                4. Find gas stations or other stops along the route

                URL Schemes:
                - Use 'waze://' to open the main app
                - Use 'waze://place?q=LOCATION' to search for a specific place
                - Use 'waze://nav?q=DESTINATION' to start navigation
                - Use 'waze://search?q=QUERY' to perform a search
            """.trimIndent(),
            urlSchemes = listOf(
                "waze://", // Main Waze URL scheme
                "waze://place", // For specific places
                "waze://nav", // For navigation
                "waze://search" // For search
            )
        ),
        
        // Amazon app
        "com.amazon.mShop.android.shopping" to AppInstructionInfo(
            instructions = """                        
                important instructions on how to use amazon app:
                
                Use the search bar to find specific products, but try variations on the search.
                Don't just search for exact description user provides necessarily, you will need to search for general product sometimes, and then scroll and click in to see if it meets other requirements.
                For example: if looking for stinky fruit, look up fruit first, and then look if any are titled stinky, or description or reviews say are stinky.
                ALWAYS scroll/swipe to look at products, don't just pick the first one
                Click on the product to read information about it, including ratings, may need to click in a few levels.
                If asked, look for specific detailed in descriptions or reviews after clicking in and scrolling
                Consider if the query mentions a specific brand, in which case, ensure items you are reviewing match that specific brand 
                Add items to cart if asked
                Always ignore sponsored items, only view non sponsored
                Always scroll several items, swiping down the screen. Never stop at first screen.

                URL Schemes:
                - Use 'amzn://' to open the main app
                - Use 'amzn://apps/android' for app-specific features
                - Use 'amzn://gp/product/ASIN' to open a specific product
                - Use 'amzn://gp/search?k=QUERY' to perform a search
            """.trimIndent(),
            urlSchemes = listOf(
                "amzn://", // Main Amazon URL scheme
                "amzn://apps/android", // For app-specific URLs
                "amzn://gp/product", // For product URLs
                "amzn://gp/search" // For search URLs
            )
        ),
        
        // Ride-sharing (Uber)
        "com.ubercab" to AppInstructionInfo(
            instructions = """
                Uber is a ride-sharing app that connects riders with drivers.
                
                Key features:
                - Request rides to specific destinations
                - Choose from different ride options (UberX, Comfort, Black, etc.)
                - View estimated fare and arrival time before confirming
                - Track driver's location in real-time
                - Rate drivers and provide feedback after rides
                - View ride history and receipts
                
                When using Uber:
                1. Enter destination in the "Where to?" field
                2. Select the type of ride desired
                3. Confirm pickup location (adjust if needed)
                4. Review price estimate and confirm ride
                5. Track driver's approach and communicate if necessary
                6. Verify driver and vehicle details before entering

                URL Schemes:
                - Use 'uber://' to open the main app
                - Use 'uber://request?pickup=LOCATION' to request a ride
                - Use 'uber://riders' to access rider-specific features
                - Use 'uber://history' to view ride history
            """.trimIndent(),
            urlSchemes = listOf(
                "uber://", // Main Uber URL scheme
                "uber://request", // For requesting rides
                "uber://riders", // For rider-specific features
                "uber://history" // For ride history
            )
        ),

        "com.instagram.android" to AppInstructionInfo(
            instructions = """
                Instagram is a photo and video sharing platform.
                
                Key features:
                - Browse photos and videos in feed
                - Share photos and stories
                - Direct messaging
                - Explore page for discovery
                - Reels for short-form video
                - Shopping features
                
                When using Instagram:
                1. Use the search bar to find specific accounts, hashtags, or locations
                2. Browse different tabs (Feed, Explore, Reels, Profile)
                3. For shopping: Browse product catalogs and shop posts
                4. For stories: View and interact with temporary content
                5. For direct messages: Access private conversations
                6. For explore: Discover new content based on interests

                URL Schemes:
                - Use 'instagram://' to open the main app
                - Use 'instagram://user?username=USERNAME' to view a profile
                - Use 'instagram://media?id=MEDIA_ID' to view a post
                - Use 'instagram://story?story_id=STORY_ID' to view a story
                - Use 'instagram://direct' to open direct messages
            """.trimIndent(),
            urlSchemes = listOf(
                "instagram://", // Main Instagram URL scheme
                "instagram://user", // For user profiles
                "instagram://media", // For media posts
                "instagram://story", // For stories
                "instagram://direct" // For direct messages
            )
        ),

        // Messaging Apps
        "com.whatsapp" to AppInstructionInfo(
            instructions = """
                WhatsApp is a messaging and calling platform.
                
                Key features:
                - Send text messages, photos, and videos
                - Make voice and video calls
                - Share documents and files
                - Group chats
                - Status updates
                - Business messaging
                
                When using WhatsApp:
                1. Use the search bar to find specific chats or contacts
                2. Navigate between different tabs (Chats, Status, Calls)
                3. For group chats: Manage members and settings
                4. For media sharing: Access gallery or camera
                5. For business chats: Look for verified business accounts
                6. For status: View and share temporary updates

                URL Schemes:
                - Use 'whatsapp://' to open the main app
                - Use 'whatsapp://send?phone=PHONE_NUMBER' to start a chat
                - Use 'whatsapp://group' to create a group
                - Use 'whatsapp://status' to view status updates
            """.trimIndent(),
            urlSchemes = listOf(
                "whatsapp://", // Main WhatsApp URL scheme
                "whatsapp://send", // For sending messages
                "whatsapp://group", // For group chats
                "whatsapp://status" // For status updates
            )
        ),

        // Food Delivery Apps
        "com.doorDash" to AppInstructionInfo(
            instructions = """
                DoorDash is a food delivery platform.
                
                Key features:
                - Order food from local restaurants
                - Track delivery in real-time
                - Browse restaurant menus
                - Save favorite orders
                - Schedule future orders
                - View delivery history
                
                When using DoorDash:
                1. Enter delivery address or use current location
                2. Browse restaurants or search for specific cuisines
                3. View restaurant menus and ratings
                4. Customize orders and add special instructions
                5. Review order before checkout
                6. Track delivery status after ordering
                7. Rate and review orders after delivery

                URL Schemes:
                - Use 'doordash://' to open the main app
                - Use 'doordash://restaurant/RESTAURANT_ID' to view a restaurant
                - Use 'doordash://order' to view current order
                - Use 'doordash://search' to search for restaurants
            """.trimIndent(),
            urlSchemes = listOf(
                "doordash://", // Main DoorDash URL scheme
                "doordash://restaurant", // For restaurant pages
                "doordash://order", // For order tracking
                "doordash://search" // For restaurant search
            )
        ),

        // Music Streaming Apps
        "com.aspiro.wamp" to AppInstructionInfo(
            instructions = """
                Tidal is a high-fidelity music streaming platform.
                
                Key features:
                - Stream high-quality music (HiFi and Master quality)
                - Access exclusive content and live performances
                - Create and share playlists
                - Download for offline listening
                - Browse music videos and live concerts
                - Connect with artists and other music lovers
                
                When using Tidal:
                1. Use the search bar to find specific songs, artists, or albums
                2. Browse different sections (Home, Search, My Collection, Explore)
                3. For playlists: Create, edit, and share collections
                4. For discovery: Use radio and recommended playlists
                5. For videos: Access music videos and live performances
                6. For social: Share music and follow artists
                7. For quality: Switch between Normal, HiFi, and Master quality settings

                URL Schemes:
                - Use 'tidal://' to open the main app
                - Use 'tidal://track/TRACK_ID' to play a track
                - Use 'tidal://artist/ARTIST_ID' to view an artist
                - Use 'tidal://album/ALBUM_ID' to view an album
                - Use 'tidal://playlist/PLAYLIST_ID' to view a playlist
                - Use 'tidal://video/VIDEO_ID' to watch a music video
            """.trimIndent(),
            urlSchemes = listOf(
                "tidal://", // Main Tidal URL scheme
                "tidal://track", // For tracks
                "tidal://artist", // For artists
                "tidal://album", // For albums
                "tidal://playlist", // For playlists
                "tidal://video" // For music videos
            )
        ),

        // Payment Apps
        "com.squareup.cash" to AppInstructionInfo(
            instructions = """
                Cash App is a peer-to-peer payment platform.
                
                Key features:
                - Send and receive money instantly
                - Cash Card (debit card)
                - Invest in stocks and Bitcoin
                - Direct deposit
                - Cash Boost rewards
                - Business payments
                
                When using Cash App:
                1. Use the search bar to find specific users or businesses
                2. Navigate between different tabs (Home, Activity, Investing, Cash Card)
                3. For payments: Enter amount and select recipient
                4. For investing: Browse stocks and Bitcoin options
                5. For Cash Card: Manage card settings and boosts
                6. For business: Access business-specific features
                7. For security: Enable security features like Face ID/Touch ID

                URL Schemes:
                - Use 'cashapp://' to open the main app
                - Use 'cashapp://pay' to initiate a payment
                - Use 'cashapp://card' to manage Cash Card
                - Use 'cashapp://investing' to access investing features
                - Use 'cashapp://business' for business features
            """.trimIndent(),
            urlSchemes = listOf(
                "cashapp://", // Main Cash App URL scheme
                "cashapp://pay", // For payments
                "cashapp://card", // For Cash Card
                "cashapp://investing", // For investing
                "cashapp://business" // For business features
            )
        ),

        "com.squareup.squarepointofsale" to AppInstructionInfo(
            instructions = """
                Square Point of Sale is a payment processing app for businesses.
                
                Key features:
                - Process card payments
                - Accept contactless payments
                - Manage inventory
                - Track sales and analytics
                - Send digital receipts
                - Manage employees and permissions
                
                When using Square POS:
                1. Use the main screen to process payments
                2. Navigate between different sections (Payments, Items, Reports, Settings)
                3. For payments: Process card, cash, or digital payments
                4. For inventory: Manage items and stock levels
                5. For reports: View sales data and analytics
                6. For employees: Manage staff access and permissions
                7. For settings: Configure payment methods and business details

                URL Schemes:
                - Use 'square://' to open the main app
                - Use 'square://payment' to process a payment
                - Use 'square://inventory' to manage inventory
                - Use 'square://reports' to view reports
                - Use 'square://settings' to access settings
            """.trimIndent(),
            urlSchemes = listOf(
                "square://", // Main Square URL scheme
                "square://payment", // For payments
                "square://inventory", // For inventory
                "square://reports", // For reports
                "square://settings" // For settings
            )
        )
    )

    /**
     * Retrieves instructions for a specific app based on its package name.
     * If no exact match is found, tries to match based on package name patterns.
     *
     * @param packageName The package name of the app
     * @return The instructions as a string, or null if no instructions exist for the package
     */
    fun getInstructions(packageName: String): String? {
        System.out.println("GETTING INSTRUCTIONS FOR: " + packageName)
        // First try exact match
        appInstructionsMap[packageName]?.let { return it.instructions }
        
        // If no exact match, try pattern matching
        val lowercasePackage = packageName.lowercase()
        
        return when {
            // Maps apps
            lowercasePackage.contains("map") || 
            lowercasePackage.contains("navigation") || 
            lowercasePackage.contains("gps") -> """
                This appears to be a mapping or navigation app.
                
                When using mapping apps:
                1. First determine if the user wants to find a location or get directions
                2. Use the search bar to enter addresses or place names
                3. For directions, enter both starting point and destination
                4. Look for options to change transportation mode (driving, walking, transit)
                5. Check for additional features like traffic information or nearby places
                
                IMPORTANT: Don't just search for A to B, but search for separate places first.
                Add stops if needed. Map search is not general search.
            """.trimIndent()
            
            // Shopping apps
            lowercasePackage.contains("shop") || 
            lowercasePackage.contains("store") || 
            lowercasePackage.contains("market") || 
            lowercasePackage.contains("buy") -> """
                This appears to be a shopping or e-commerce app.
                
                When using shopping apps:
                1. Use the search bar to find specific products
                2. Browse categories if available
                3. Check product details, prices, and reviews before adding to cart
                4. Review cart contents before checkout
                5. Look for shipping options and payment methods during checkout
            """.trimIndent()
            
            // Social media apps
            lowercasePackage.contains("social") || 
            lowercasePackage.contains("chat") || 
            lowercasePackage.contains("message") || 
            lowercasePackage.contains("community") -> """
                This appears to be a social media or messaging app.
                This can be used for research or answering a user query                
            """.trimIndent()
            
            // Food delivery apps
            lowercasePackage.contains("food") || 
            lowercasePackage.contains("delivery") || 
            lowercasePackage.contains("order") || 
            lowercasePackage.contains("restaurant") -> """
                This appears to be a food delivery or restaurant app.
                
                When using food delivery apps:
                1. Confirm location or delivery address
                2. Browse restaurants or search for specific cuisines
                3. View menus and select items
                4. Customize orders as needed
                5. Review order before checkout
                6. Select payment method and complete order
                7. Track delivery status after ordering
            """.trimIndent()
            
            // Transportation/ride-sharing apps
            lowercasePackage.contains("ride") || 
            lowercasePackage.contains("taxi") || 
            lowercasePackage.contains("transit") || 
            lowercasePackage.contains("transport") -> """
                This appears to be a transportation or ride-sharing app.
                
                When using transportation apps:
                1. Enter destination in the search field
                2. Confirm pickup location
                3. Review available ride options and prices
                4. Select preferred option and confirm ride
                5. Track vehicle approach and verify driver information
                6. Check for payment options and ride history features
            """.trimIndent()
            
            // Banking/payment apps
            lowercasePackage.contains("bank") || 
            lowercasePackage.contains("pay") || 
            lowercasePackage.contains("finance") || 
            lowercasePackage.contains("money") -> """
                This appears to be a banking or payment app.
                
                When using banking/payment apps:
                1. Check for account balance and transaction history
                2. For sending money: Look for transfer or payment options
                3. For receiving money: Look for request money features
                4. Verify recipient information carefully before confirming transactions
                5. Check for security features like biometric authentication
                6. Look for bill payment or scheduled payment options if needed
            """.trimIndent()

            else -> {
                // finally we will see if there is more general advice
                val category = IntentAppKinds.getCategoryForPackage(packageName)
                if (category != null && category.generalUsageInstructions.isNotEmpty()) {
                    return category.generalUsageInstructions
                } else {
                    return null
                }
            }
        }
    }

    /**
     * Retrieves URL schemes for a specific app based on its package name.
     *
     * @param packageName The package name of the app
     * @return List of URL schemes supported by the app, or empty list if none found
     */
    fun getUrlSchemes(packageName: String): List<String> {
        return appInstructionsMap[packageName]?.urlSchemes ?: emptyList()
    }
}

/**
 * Data class to hold both instructions and URL schemes for an app
 */
private data class AppInstructionInfo(
    val instructions: String,
    val urlSchemes: List<String>
)
