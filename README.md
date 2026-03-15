# Tubes 1 Strategi Algoritma - STIMA Battle Bot

Repositori ini berisi implementasi source code bot untuk permainan STIMA-battle. Terdapat tiga buah bot yang dikembangkan menggunakan pendekatan Algoritma Greedy.

## Penjelasan Singkat Algoritma Greedy

Berikut adalah penjelasan algoritma greedy yang diimplementasikan pada masing-masing bot:

1. **GreedyPainter**
   Bot ini menggunakan strategi greedy yang berfokus pada pewarnaan (painting) area. Pada setiap gilirannya, bot akan mengevaluasi semua kemungkinan langkah dan selalu memilih langkah yang akan langsung menghasilkan jumlah petak/area terwarnai paling banyak saat itu juga.

2. **GreedyExpansion**
   Bot ini mengimplementasikan algoritma greedy dengan prioritas pada ekspansi wilayah. Bot akan selalu memilih pergerakan menuju area kosong atau node terdekat yang belum dikuasai. Keputusan diambil murni berdasarkan jarak terpendek atau cost terkecil untuk memperluas jangkauan tanpa mempertimbangkan ancaman jangka panjang.

3. **GPBSM (Main Bot)**
   Sebagai bot utama, GPBSM mengkombinasikan metrik evaluasi yang lebih komprehensif. Pendekatan greedy pada bot ini tidak hanya melihat satu aspek, melainkan menghitung bobot keuntungan maksimum dari setiap aksi yang mungkin dilakukan (seperti mengecat, menyerang musuh, atau mengambil power-up). Bot akan mengeksekusi aksi dengan nilai kalkulasi greedy tertinggi pada giliran tersebut.

## Requirement Program

Untuk dapat melakukan kompilasi dan menjalankan program ini, pastikan sistem Anda telah terpasang perangkat lunak berikut:
* Java Development Kit (JDK)
* Gradle
* Git

## Langkah-langkah Kompilasi dan Build Program

Repositori ini hanya memuat source code dari bot. Untuk menjalankan bot, Anda harus menggunakan game engine yang telah dimodifikasi. Berikut adalah panduan instalasi dan kompilasinya:

1. Lakukan clone pada repositori game engine yang telah disediakan:
   ```bash
   git clone https://github.com/Fariz36/STIMA-battle
   cd STIMA-battle
   ```

2. Integrasikan bot ke dalam engine:
   Salin atau pindahkan source code bot (`greedypainter`, `greedyexpansion`, dan `gpbsm`) dari repositori ini ke dalam direktori pengembangan bot yang ada di dalam folder `STIMA-battle` tersebut.

3. Lakukan build pada program menggunakan Gradle dengan menjalankan perintah:
   ```bash
   ./gradlew build
   ```

4. Masuk ke dalam direktori client:
   ```bash
   cd client
   ```

5. Jalankan aplikasi hasil build yang terdapat pada direktori tersebut.

6. Konfigurasi pada aplikasi:
   Setelah aplikasi berhasil berjalan, Anda akan diminta untuk memilih direktori root. Pilihlah direktori `STIMA-battle` sebagai direktori root (pastikan Anda **bukan** memilih `STIMA-battle/src`).

## Author

* 18223057 - Stanislaus Ardy Bramantyo
* 18223120 - Leonard Arif Sutiono
* 18223129 - Izhar Alif Akbar
