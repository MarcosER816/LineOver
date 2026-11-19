package dev.mark.lineover.core.io.lens

/** One file of the Lensfun database: its name, size in bytes and SHA-256 (hex). */
data class LensfunFile(
    val name: String,
    val size: Long,
    val sha256: String,
)

/**
 * The Lensfun lens database LineOver downloads on request (Stage 9): the `data/db` files of
 * one pinned commit, each checked against its SHA-256 before use. The database is licensed
 * under CC BY-SA 3.0 (the Lensfun contributors); LineOver uses its own code, not the LGPL
 * Lensfun library. Generated from the files at [COMMIT]; change all three together.
 */
object LensfunManifest {
    const val COMMIT = "37238fcef563196fb798f6151cb9023968b26c40"
    const val BASE_URL = "https://raw.githubusercontent.com/lensfun/lensfun/$COMMIT/data/db/"
    const val LICENCE = "CC BY-SA 3.0"
    const val ATTRIBUTION = "Lens profiles: the Lensfun database, © the Lensfun contributors, CC BY-SA 3.0"
    const val TOTAL_BYTES = 5215981L

    val FILES =
        listOf(
            LensfunFile("6x6.xml", 29550, "ca13c2756b32d728675372695c072517a0e38af5eff2ecd3e17599dee7fc58b3"),
            LensfunFile("actioncams.xml", 17188, "79a5da0322574b95690464444c9d9ba1da0e1f827da44fd6bff120a0ec10e4b4"),
            LensfunFile("compact-canon.xml", 247046, "bae47e4b3a0f8852641038ae2dcca5b28ed7965e65ca795fd676f9de857c4670"),
            LensfunFile("compact-casio.xml", 6889, "ab35433879c4574ef8f97cd4e3d114d7ff8db4e7279da9d502fc9e911b51ee27"),
            LensfunFile("compact-fujifilm.xml", 67954, "58af9ebef2410c428ae2c5d01003d2247330d47d918823e63522b4d897a997d1"),
            LensfunFile("compact-kodak.xml", 2091, "377018ccd74f58e966b37a11e5a8689be4268153bebece9f1f2a0dbfa8234845"),
            LensfunFile("compact-konica-minolta.xml", 13349, "832a7473951ab625e10c48af6cf60f89b57280016d45729e2584cf9e41ad3cc7"),
            LensfunFile("compact-leica.xml", 9113, "8e4676679377576dc19e7900ce86fc69b0248ea1d4538aa22ee1ee09431a3be3"),
            LensfunFile("compact-nikon.xml", 40003, "5393bdb8223e13d21b85818e96571e97c035470b49b0452dfcedc2e1ff512d3b"),
            LensfunFile("compact-olympus.xml", 34454, "7637d469ad54a02ebce9492a9aa9424a2ff1ec07b0398158076f762fdc08389b"),
            LensfunFile("compact-panasonic.xml", 70621, "f191fc2a5d49fb50c06f4c399ee8c1a83a05f93a0efaecd352ec5c1479b8e60b"),
            LensfunFile("compact-pentax.xml", 6689, "596fae3edaadcf2d90ae930fc1bec42597ca0b2bd11842e686e1b915f3736390"),
            LensfunFile("compact-ricoh.xml", 18424, "46df96ebc367d5170cb6b7dfc3742093f0ad946264518fc5488573ba052f29d7"),
            LensfunFile("compact-samsung.xml", 13875, "36e3a778046d56385c387d48474cd32a89a5da94d99e640b5ce716ce747f898d"),
            LensfunFile("compact-sigma.xml", 2395, "0ddc265e3e580da05dcd4664d1b9c2464838d74dd02519c826520950c8f1759e"),
            LensfunFile("compact-sony.xml", 126390, "6b1e5ec3710915f04eceffb87eafdfded4b56d5b4bc58d5086ae59f7d3c066f0"),
            LensfunFile("contax.xml", 1696, "5eba12e6fd4913470afb6fe25fee47a263084b6cec830297273909872f47669a"),
            LensfunFile("generic.xml", 5416, "cd572d4110981546b2c4cd296c2ff16755b30063fa136d8b06ad2ca13ab4c59f"),
            LensfunFile("mil-canon.xml", 247994, "2de8e9467a6af6f83cc9da834d3d3b94d1aaf54519f60c68ac6bc02780a77516"),
            LensfunFile("mil-fujifilm.xml", 213330, "cc8fd1d595bf6b57ea2e4f0bbdc30f5ac8af4867fad29efe445a79c90c2d2177"),
            LensfunFile("mil-hasselblad.xml", 1048, "13e1fa55d36c2ed7d22964c21ce6c21d887bfe4506e840e425da1ce4157c3756"),
            LensfunFile("mil-leica.xml", 11663, "c691037e2e0743388473c2a0545e340462c412b7e3b57e451f4f9c89dde2388a"),
            LensfunFile("mil-nikon.xml", 187918, "3814e192a9f599847eefeea6163d26186d8107cb794f33e4724803e476e45cea"),
            LensfunFile("mil-olympus.xml", 147123, "989a3d88ef07a286efc2755df214792fdf71d60366932a1b369a77ad39cf2abb"),
            LensfunFile("mil-panasonic.xml", 299264, "eb5f01f33e65721f3900786d5970c7e220dd042aaa56968be33c009a358ff3c5"),
            LensfunFile("mil-pentax.xml", 1385, "675a94f52794868ad9f1d79e17b7128783c34b3db23e9eac346c5963812a16e5"),
            LensfunFile("mil-samsung.xml", 13007, "10130e9e125e0882a16fd3033cfed0175e9c14cb7d35e630cdb94736f27e5dc3"),
            LensfunFile("mil-samyang.xml", 25990, "ae3bf262f12facdc6f81b837cf6d36fdde2a930d2bfb0918d5abaa47fcfea3ab"),
            LensfunFile("mil-sigma.xml", 125392, "b7dc276cc8ae896305892002ff446f4d1e8bfef7f2d1775f8e013bd9a10f66ea"),
            LensfunFile("mil-sony.xml", 274541, "f9f7e5d7461ac58f76eb55dcc5b211f2e9a451402ddb4a9ecb12d1e4dc04f2b8"),
            LensfunFile("mil-tamron.xml", 108177, "64077167f06e25b1a9393eaefd8ceb9b288c7ad20b046c2d97c83fbca536b6b6"),
            LensfunFile("mil-tokina.xml", 1014, "addb27bd82b0d729644b9da1342f92401f63cc5945309ed17f38f4b7af1649e3"),
            LensfunFile("mil-zeiss.xml", 19171, "ef8a238904fc9ea6fdb6171863dfd70cd94814af19ecd8c31ee000ca1864589d"),
            LensfunFile("misc.xml", 176039, "2000a347d9dc37d908ea5b56c760de5b2b4ab475857959ae2e901c31339b01bb"),
            LensfunFile("om-system.xml", 45205, "ab7cfcfd4c4023f7d0affd87a971e93a391d4d11952b9bf0436aa7967e68880e"),
            LensfunFile("rf-leica.xml", 6712, "f93636e1980ad308d1873cc3ac078cdc77c0d529d777cd45c5c8d0e4781981e3"),
            LensfunFile("slr-canon.xml", 780353, "5244e6df8f2522f829c57e2073313ce31a624e9ed9605310fe43613b9d588c99"),
            LensfunFile("slr-hasselblad.xml", 642, "97602c467699904bf101179ea2fa24f05f3e1879250498a6ba306cd95a3b7a6b"),
            LensfunFile("slr-konica-minolta.xml", 47170, "5639ca94f121f609247c2c120b65830b3ef7784db6ecfa7e8eb3d29ff5046c1b"),
            LensfunFile("slr-leica.xml", 432, "c5e74cb511712e99ed60a066e85c842fea58f693c5039c3819485619f5293d0e"),
            LensfunFile("slr-nikon.xml", 551265, "aa770005b85567721038456183cfdbf6b6cea51b33a399b291f447287b9e91db"),
            LensfunFile("slr-olympus.xml", 116602, "f7693b6779edd8ed10d7dc23113669255bfa4987cea7db0e843f7f5f2bbbe615"),
            LensfunFile("slr-panasonic.xml", 419, "cd79acb49d9ce5f2a4cecc572b3e117f03da380bed2dcbfd52327192d8ce2381"),
            LensfunFile("slr-pentax.xml", 210117, "93be1fe9263e06d7d69a8a6ae10cb52f23cad08ddc44f57f749e1ca288bc0c6e"),
            LensfunFile("slr-ricoh.xml", 2869, "c835798136a53535fdf4f81312ac9aad0ea3de3ef732c183d8cc4d2f432011d9"),
            LensfunFile("slr-samsung.xml", 1110, "dc0a29e424ff96e4316ddeb76126fa6f6c73a8836905a3e1f56f0c493826148f"),
            LensfunFile("slr-samyang.xml", 16811, "fb1585bea91f5f59e1fae8811dd76c92252ee5d52e3647df2aff4695048443b5"),
            LensfunFile("slr-schneider.xml", 5890, "de607cfe4ba6e7debc61e282ce39b72825c07601572e14af2bc9108ba67faf81"),
            LensfunFile("slr-sigma.xml", 444640, "f55f41c144fbb72e3c51d2dfef40e918930d37db1b4150bd02bd746406c036d2"),
            LensfunFile("slr-soligor.xml", 573, "1aba0aab19bce87ea86175b6551cb5306a512eb54be1698b89991b86008f6a04"),
            LensfunFile("slr-sony.xml", 88865, "e5ca7f532afe23ff380bf2f9e5458f687bed603355d73a254d85c4e692942fdc"),
            LensfunFile("slr-tamron.xml", 201992, "c62090789cebe6bbb663111c6e97beeb1111852bb735088e29cf8782df4bab45"),
            LensfunFile("slr-tokina.xml", 91977, "b1213eda81a613bc3976340398c6b8ce8b7dc29423476fc565d5f8e5cf612ecf"),
            LensfunFile("slr-ussr.xml", 19024, "1fd6acc8a8f40373379bea2899ffd96fc34b22d9b044d0474dabd4c89af44efb"),
            LensfunFile("slr-vivitar.xml", 2157, "c5c67e3fe38e8fa19044acadc7730047690435f66aac563f55ffe04997a88111"),
            LensfunFile("slr-zeiss.xml", 14957, "49aa211a41fd5fe391d26af5fa65bdf9428d60863ffff15d3916fa1c520dc101"),
        )
}
