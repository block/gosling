package xyz.block.gosling.features.agent

/**
 * some constants around what apps can do (installed apps)
 */

// Array of payment app package names
val paymentAppPackageNames = arrayOf(
    "com.google.android.apps.nbu.paisa.user", // Google Pay
    "net.one97.paytm", // Paytm
    "com.phonepe.app", // PhonePe
    "in.amazon.mShop.android.shopping", // Amazon Pay
    "in.org.npci.upiapp", // BHIM
    "com.mobikwik_new", // Mobikwik
    "com.freecharge.android", // Freecharge
    "com.samsung.android.spay", // Samsung Pay
    "com.paypal.android.p2pmobile", // PayPal
    "com.venmo", // Venmo
    "com.squareup.cash", // Cash App
    "com.zellepay.zelle", // Zelle
    "com.eg.android.AlipayGphone", // Alipay
    "com.tencent.mm", // WeChat Pay
    "com.moneybookers.skrillpayments", // Skrill
    "com.revolut.revolut", // Revolut
    "com.squareup", // Square Point of Sale
    "com.stripe.android.dashboard", // Stripe Dashboard
    "com.myklarnamobile", // Klarna
    "com.afterpaymobile", // Afterpay
    "com.google.android.apps.walletnfcrel", // Google Wallet
    "com.barclays.android.barclaysmobilebanking", // Barclays Mobile Banking
    "com.chase.sig.android", // Chase Mobile
    "com.wf.wellsfargomobile", // Wells Fargo Mobile
    "com.infonow.bofa", // Bank of America Mobile Banking
    "com.konylabs.capitalone", // Capital One Mobile
    "com.htsu.hsbcpersonalbanking", // HSBC Mobile Banking
    "com.citi.citimobile", // Citi Mobile
    "com.csam.icici.bank.imobile", // ICICI Bank iMobile
    "com.snapwork.hdfc", // HDFC Bank MobileBanking
    "com.axis.mobile", // Axis Mobile
    "com.msf.kbank.mobile", // Kotak Mobile Banking
    "com.sbi.lotusintouch", // SBI YONO
    "com.infrasofttech.pnb", // PNB ONE
    "com.fss.uboi.mobilebanking", // Union Bank of India
    "com.idbi.mobilebanking", // IDBI Bank GO Mobile+
    "com.yesbank", // YES BANK Mobile
    "com.indusind.mobilebanking", // IndusInd Bank
    "com.sc.digitallife", // Standard Chartered Mobile
    "com.dbs.in.digitalbank", // DBS digibank
    "rbl.bank.mobile", // RBL MoBank
    "com.aufin.bank", // AU Mobile Banking
    "com.fss.uco", // UCO mBanking Plus
    "com.infrasoft.canarabank", // Canara Bank Mobile Banking
    "com.bankofindia.boimobile", // BOI Mobile
    "com.pnb.mb", // PNB mBanking
    "com.uboi.mobile", // Union Bank UMobile
    "com.idfcfirstbank.mobilebanking", // IDFC FIRST Bank Mobile Banking
    "com.bandhan.bank.mobilebanking" // Bandhan Bank mBandhan
)
val travelBookingPackageNames = arrayOf(
    "com.booking", // Booking.com
    "com.expedia.bookings", // Expedia
    "com.hotels.android", // Hotels.com
    "com.tripadvisor.tripadvisor", // TripAdvisor
    "com.kayak.android", // KAYAK
    "com.priceline.android.negotiator", // Priceline
    "com.hotwire.hotels", // Hotwire
    "com.orbitz", // Orbitz
    "com.travelocity", // Travelocity

)
val foodOrderingBookingPackageNames = arrayOf(
    "com.doordash.driverapp", // DoorDash
    "com.ubercab.eats", // Uber Eats
    "com.grubhub.android", // Grubhub
    "com.postmates.android", // Postmates
    "com.seamless.android", // Seamless
    "com.yelp.android", // Yelp
    "com.opentable", // OpenTable

)

// Array of ecommerce app package names
val ecommerceAppPackageNames = arrayOf(
    "com.amazon.mShop.android.shopping", // Amazon Shopping
    "com.ebay.mobile", // eBay
    "com.walmart.android", // Walmart
    "com.target.ui", // Target
    "com.alibaba.aliexpresshd", // AliExpress
    "com.wish.android", // Wish
    "com.etsy.android", // Etsy
    "com.shopify.mobile", // Shopify
    "com.wayfair.wayfair", // Wayfair
    "com.bestbuy.android", // Best Buy
    "com.newegg.app", // Newegg
    "com.zzkko", // SHEIN
    "com.contextlogic.wish", // Wish
    "com.zappos.android", // Zappos
    "com.groupon", // Groupon
    "com.overstock", // Overstock
    "com.ikea.kompis", // IKEA
    "com.nike.commerce.snkrs.android", // Nike SNKRS
    "com.adidas.app", // Adidas
    "com.macys.android", // Macy's
    "com.kohls.mcommerce.opal", // Kohl's
    "com.nordstrom.app", // Nordstrom
    "com.jcpenney.android", // JCPenney
    "com.sephora", // Sephora
    "com.ulta.android", // Ulta Beauty
    "com.homedepot", // Home Depot
    "com.lowes.android", // Lowe's
    "com.staples.android", // Staples
    "com.officedepot.retail", // Office Depot
    "com.costco.app.android", // Costco
    "com.samsclub.app", // Sam's Club
    "com.hm.goe", // H&M
    "com.zara", // Zara
    "com.uniqlo.app.us", // Uniqlo
    "com.gap.android", // GAP
    "com.oldnavy.android", // Old Navy
    "com.abercrombie.apps", // Abercrombie & Fitch
    "com.ae.ae", // American Eagle
    "com.forever21.android", // Forever 21
    "com.victoriassecret.app", // Victoria's Secret
    "com.tiffany.android", // Tiffany & Co.
    "com.gucci.gucciapp", // Gucci
    "com.louisvuitton.lvapp", // Louis Vuitton
    "com.chanelofficial.coco", // Chanel
    "com.burberry.android", // Burberry
    "com.prada.android", // Prada
    "com.versace.android", // Versace
    "com.dolcegabbana.android", // Dolce & Gabbana
    "com.armani.android", // Armani
    "com.rolex.android", // Rolex
    "com.cartier.android", // Cartier
    "com.omega.android", // Omega
    "com.tagheuer.android", // TAG Heuer
    "com.apple.store", // Apple Store
    "com.samsung.ecomm", // Samsung Shop
    "com.microsoft.emmx", // Microsoft Store
    "com.dell.mobileapp", // Dell
    "com.hp.android.printservice", // HP
    "com.lenovo.lenovoapp", // Lenovo
    "com.asos.app", // ASOS
    "com.instacart.client", // Instacart
    "com.petco.petco", // Petco
    "com.petsmart.android", // PetSmart
    "com.walgreens.loyalty", // Walgreens
    "com.riteaid.android", // Rite Aid
    "com.kroger.mobile", // Kroger
    "com.cheaptickets.android", // CheapTickets
    "com.vrbo.android", // Vrbo
    "com.hipmunk.android", // Hipmunk
    "com.thumbtack.consumer", // Thumbtack
    "com.craigslist.app", // Craigslist
    "com.facebook.marketplace", // Facebook Marketplace
    "com.mercariapp.mercari", // Mercari
    "com.poshmark.app", // Poshmark
    "com.tradesy.android", // Tradesy
    "com.grailed", // Grailed
    "com.stockx.android", // StockX
    "com.goat.android", // GOAT
    "com.farfetch.farfetchshop", // Farfetch
    "com.mytheresa", // Mytheresa
    "com.net.a.porter", // NET-A-PORTER
    "com.mrporter.mrporter", // MR PORTER
    "com.ssense.android", // SSENSE
    "com.modaoperandi.app", // Moda Operandi
    "com.matchesfashion.android", // MATCHESFASHION
    "com.alibaba.intl.android.apps.poseidon", // Alibaba.com
    "com.dhgate.buyer", // DHgate
    "com.banggood.client", // Banggood
    "com.gearbest.app", // Gearbest
    "com.joom", // Joom
    "com.lightinthebox.android", // LightInTheBox
    "com.temu.app", // Temu
    "com.klarna.mobile.app", // Klarna
    "com.affirm.affirm", // Affirm
    "com.shopee.ph", // Shopee
    "com.lazada.android", // Lazada
    "com.tokopedia.tkpd", // Tokopedia
    "com.bukalapak.android", // Bukalapak
    "com.flipkart.android", // Flipkart
    "jp.co.yahoo.android.yauction", // Yahoo! Japan Shopping
    "com.mercadolibre", // Mercado Libre
    "com.noon.buyerapp" // noon
)

// Array of airline app package names
val airlineAppPackageNames = arrayOf(
    "com.aa.android", // American Airlines
    "com.delta.mobile.android", // Delta Air Lines
    "com.united.mobile.android", // United Airlines
    "com.southwestairlines.mobile", // Southwest Airlines
    "com.ba.mobile", // British Airways
    "com.lufthansa.android", // Lufthansa
    "com.emirates.ek.android", // Emirates
    "com.qatarairways.mobile", // Qatar Airways
    "com.singaporeair.app", // Singapore Airlines
    "com.airfrance.android", // Air France
    "com.afklm.mobile.android", // KLM Royal Dutch Airlines
    "com.cathaypacific.cxmobile", // Cathay Pacific
    "com.turkishairlines.mobile", // Turkish Airlines
    "com.etihad.poc", // Etihad Airways
    "au.com.qantas.android", // Qantas Airways
    "com.jal.jmb", // Japan Airlines
    "com.ana.android", // All Nippon Airways (ANA)
    "ru.aeroflot.afl_app", // Aeroflot
    "com.aircanada", // Air Canada
    "com.alaskaairlines.android", // Alaska Airlines
    "com.jetblue.JetBlueAndroid", // JetBlue Airways
    "com.ryanair.cheapflights", // Ryanair
    "com.easyjet.mobile.android", // EasyJet
    "com.virginatlantic.mobile", // Virgin Atlantic
    "com.flyfrontier.android", // Frontier Airlines
    "com.spirit.customerapp", // Spirit Airlines
    "com.allegiant", // Allegiant Air
    "com.hawaiianairlines.app", // Hawaiian Airlines
    "com.aerlingus.mobile", // Aer Lingus
    "gr.aegean.android", // Aegean Airlines
    "com.airindia", // Air India
    "com.asianaairlines", // Asiana Airlines
    "com.austrian", // Austrian Airlines
    "com.avianca", // Avianca
    "br.com.tam", // Azul Airlines
    "com.bangkokairways", // Bangkok Airways
    "com.cebupacificair", // Cebu Pacific
    "com.chinaairlines.mobile", // China Airlines
    "com.ceair", // China Eastern Airlines
    "com.csair", // China Southern Airlines
    "com.copaairlines.app", // Copa Airlines
    "com.egyptair.android", // EgyptAir
    "com.ethiopianairlines.android", // Ethiopian Airlines
    "com.evaair", // EVA Air
    "com.finnair", // Finnair
    "com.garudaindonesia", // Garuda Indonesia
    "com.gulfair", // Gulf Air
    "com.hainanairlines", // Hainan Airlines
    "com.iberia.android", // Iberia
    "com.kenyaairways.mobile", // Kenya Airways
    "com.koreanair", // Korean Air
    "com.kuwaitairways", // Kuwait Airways
    "com.latam.lanpass", // LATAM Airlines
    "com.lot.mobile", // LOT Polish Airlines
    "com.malaysiaairlines", // Malaysia Airlines
    "com.norwegian.travelassistant", // Norwegian Air Shuttle
    "com.omanair", // Oman Air
    "com.philippineairlines", // Philippine Airlines
    "com.royalairmaroc", // Royal Air Maroc
    "com.rj.app", // Royal Jordanian
    "com.saudia.SaudiaApp", // Saudia
    "com.flysas", // Scandinavian Airlines
    "za.co.flysaa", // South African Airways
    "lk.srilankan.mobile", // SriLankan Airlines
    "com.swiss", // Swiss International Air Lines
    "com.tap.portugal", // TAP Air Portugal
    "com.thaiairways", // Thai Airways
    "com.vietnamairlines", // Vietnam Airlines
    "com.vistara.android" // Vistara
)