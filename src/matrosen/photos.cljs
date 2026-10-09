(ns matrosen.photos)

;; Menu-gallery matches checked against Matrosen’s labelled Wolt dishes.
;; Photographs load on demand from their source sites at preview resolution.
(def photos
  {:sill {:src "https://static.thatsup.website/37/1080/responsive-images/18394e979ca7a9b159d0bab698bf6308___media_library_original_637_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1080/18394e979ca7a9b159d0bab698bf6308.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :gravlax {:src "https://static.thatsup.website/37/1081/responsive-images/b1780a9625db8346a4926458c166aaba___media_library_original_636_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1081/b1780a9625db8346a4926458c166aaba.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :varmrokt-lax {:src "https://static.thatsup.website/37/1083/responsive-images/69460d37ee9ac18be83cd9cf2b31a29f___media_library_original_636_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1083/69460d37ee9ac18be83cd9cf2b31a29f.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :kornblomst {:src "https://static.thatsup.website/37/1084/responsive-images/adf89c05d5bc1169aa1fd2c8f883a8a9___media_library_original_637_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1084/adf89c05d5bc1169aa1fd2c8f883a8a9.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :rostbiff {:src "https://static.thatsup.website/37/1085/responsive-images/4cc99cba0bd2af24febe328084642f1c___media_library_original_637_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1085/4cc99cba0bd2af24febe328084642f1c.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :currykyckling {:src "https://static.thatsup.website/37/1086/responsive-images/101b8a2c13bb2f1b85a767170c8bab72___media_library_original_637_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1086/101b8a2c13bb2f1b85a767170c8bab72.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :leverpastej {:src "https://static.thatsup.website/37/1087/responsive-images/ba6575a977c24cf1c33bb3d872b7f05d___media_library_original_637_358.jpg?v=1621511463"
    :original "https://static.thatsup.website/37/1087/ba6575a977c24cf1c33bb3d872b7f05d.jpg?v=1621511463"
    :source "https://matrosensmorrebrod.com/meny" :credit "Matrosens meny"}
   :avokado {:src "https://imageproxy.wolt.com/menu/menu-images/shared/10fe05d2-467e-11ee-8b6d-e661015c4b6d_stockholm_avocado_med_pochert_a_gg_4.jpg?w=600"
    :original "https://imageproxy.wolt.com/menu/menu-images/shared/10fe05d2-467e-11ee-8b6d-e661015c4b6d_stockholm_avocado_med_pochert_a_gg_4.jpg"
    :source "https://wolt.com/sv/swe/stockholm/restaurant/matrosen-smrrebrd" :credit "Matrosen på Wolt"}
   :brie {:src "https://imageproxy.wolt.com/menu/menu-images/shared/f1de4914-467d-11ee-a921-12f7574a5562_stockholm_dansk_brie_2.jpg?w=600"
    :original "https://imageproxy.wolt.com/menu/menu-images/shared/f1de4914-467d-11ee-a921-12f7574a5562_stockholm_dansk_brie_2.jpg"
    :source "https://wolt.com/sv/swe/stockholm/restaurant/matrosen-smrrebrd" :credit "Matrosen på Wolt"}
   :skagen {:src "https://imageproxy.wolt.com/menu/menu-images/5ece33ce1348b06b297d1e37/d020bc12-eaef-11eb-a2c9-d64a79615b3d_matrosens_skagenro_ra_1.jpeg?w=600"
    :original "https://imageproxy.wolt.com/menu/menu-images/5ece33ce1348b06b297d1e37/d020bc12-eaef-11eb-a2c9-d64a79615b3d_matrosens_skagenro_ra_1.jpeg"
    :source "https://wolt.com/sv/swe/stockholm/restaurant/matrosen-smrrebrd" :credit "Matrosen på Wolt"}
   :rodspatta {:src "https://imageproxy.wolt.com/menu/menu-images/shared/fdc8dc30-467d-11ee-9f5e-9edf5c31eca1_stockholm_friteradro_dspa_tta.jpg?w=600"
    :original "https://imageproxy.wolt.com/menu/menu-images/shared/fdc8dc30-467d-11ee-9f5e-9edf5c31eca1_stockholm_friteradro_dspa_tta.jpg"
    :source "https://wolt.com/sv/swe/stockholm/restaurant/matrosen-smrrebrd" :credit "Matrosen på Wolt"}
   })
