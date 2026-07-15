# DOKUMENTASI FITUR LENGKAP - ASTRAL TYPER (projectypeset)

Dokumen ini berisi penjelasan yang sangat lengkap dan mendetail mengenai seluruh fitur yang tersedia di aplikasi **Astral Typer** (projectypeset), mulai dari fitur dasar, menu-menu kecil, hingga fitur dan mode paling kompleks yang didukung oleh sistem.

---

## DAFTAR ISI
1. [Gambaran Umum Aplikasi](#1-gambaran-umum-aplikasi)
2. [Sistem Layer Polimorfik (StylableLayer)](#2-sistem-layer-polimorfik-stylablelayer)
3. [Panduan Menu Properti Layer (EditorActivity)](#3-panduan-menu-properti-layer-editoractivity)
4. [Mode Pengeditan Lanjutan (Advanced Editing)](#4-mode-pengeditan-lanjutan-advanced-editing)
5. [Fitur Typer Mode (Batch Typesetting)](#5-fitur-typer-mode-batch-typesetting)
6. [Manajemen Project & File (ProjectManager)](#6-manajemen-project--file-projectmanager)
7. [Fitur Ekspor & Format Output](#7-fitur-ekspor--format-output)
8. [Pengaturan Global & Watermark](#8-pengaturan-global--watermark)

---

## 1. GAMBARAN UMUM APLIKASI
**Astral Typer** adalah aplikasi typesetting dan pengeditan gambar tingkat lanjut yang dirancang khusus untuk mempermudah proses penyuntingan teks pada komik, infografis, desain promosi, maupun manipulasi gambar kreatif. Aplikasi ini menggabungkan kemampuan manipulasi teks yang sangat fleksibel dengan fitur kecerdasan buatan (AI) untuk penghapusan objek/balon teks secara otomatis serta sistem manajemen proyek berbasis folder yang rapi.

---

## 2. SISTEM LAYER POLIMORFIK (StylableLayer)
Setiap elemen visual yang ditambahkan ke kanvas berupa sebuah **Layer** yang mengimplementasikan interface `StylableLayer`. Ini memungkinkan semua jenis layer mendapatkan perlakuan pengeditan gaya (styling) dan transformasi secara polimorfik.

### Jenis-Jenis Layer:
1. **Text Layer (Layer Teks):**
   - Mendukung teks statis maupun bergaya kaya (*Rich Text/Spannable*).
   - Memiliki properti penyesuaian ukuran kotak batas teks (*boxWidth* dan *fixedHeight*).
   - Mendukung pembungkusan teks dalam pola lingkaran/oval (*Oval Shape wrapping*).
2. **Shape Layer (Layer Bentuk SVG):**
   - Memuat file bentuk berformat SVG langsung dari aset aplikasi (misal: lingkaran, hati, segitiga, persegi, segi delapan, dll).
   - Warna fill dan stroke dimanipulasi secara dinamis menggunakan ekspresi reguler (Regex) sebelum di-render ke kanvas dengan tetap menjaga transparansi warna.
3. **Image Layer (Layer Gambar):**
   - Menampung gambar eksternal yang diimpor oleh pengguna.
   - Mendukung transformasi spasial serta fitur-fitur seperti perspektif, warp, opasitas, dan penghapusan mask (*erase mask*).

### Karakteristik Umum Layer:
- **Transformasi Bebas:** Geser posisi (X, Y), Putar (*Rotation*), Skala (*Scale X, Scale Y*), dan Pencerminan (*Mirroring Horizontal/Vertical*).
- **Properti Manajemen:** Visibilitas (*Visible/Hidden*), Kunci Layer (*Locked/Unlocked*), Pengubahan nama layer, Opasitas (0-255), serta Mode Campuran (*Blend Mode*).

---

## 3. PANDUAN MENU PROPERTI LAYER (EditorActivity)
Saat sebuah layer dipilih pada kanvas, panel bawah akan menampilkan menu properti yang relevan secara dinamis. Berikut adalah penjelasan lengkap seluruh menu properti beserta fungsi-fungsinya:

### A. Quick Edit (Sunting Cepat)
*Hanya tersedia untuk Text Layer.*
Menu ini memfokuskan pengguna langsung ke pengeditan isi teks dengan antarmuka pengetikan khusus yang meminimalkan gangguan visual.
- **Fungsi Utama:** Menyediakan kolom input teks real-time yang menjaga format dekorasi (*spans*).
- **Tombol Pintasan Format Kasus Huruf:**
  - `UPPERCASE` (Mengubah seluruh teks terpilih menjadi huruf kapital).
  - `lowercase` (Mengubah seluruh teks terpilih menjadi huruf kecil).
  - `Capitalize Each Word` (Mengubah huruf pertama dari setiap kata menjadi huruf besar).
- **Toggle Bentuk Wadah Teks:** Tombol cepat untuk mengubah pembungkusan teks antara bentuk Persegi (*Rectangle*) atau Oval/Lingkaran (*Oval Shape*).
- **Fungsi Perataan Instan:** Tombol perataan kiri, tengah, kanan, dan checklist selesai.

### B. Font (Pilihan Huruf)
*Hanya tersedia untuk Text Layer.*
Menu pemilihan jenis huruf yang terbagi dalam tiga tab pencarian dinamis:
- **Tab Standard:** Menampilkan koleksi font bawaan sistem.
- **Tab My Font:** Menampilkan font kustom yang diimpor secara manual oleh pengguna (Mendukung file tunggal `.ttf`, `.otf`, atau file arsip `.zip` berisi banyak font).
- **Tab Favorite:** Daftar font yang telah ditandai bintang (★) oleh pengguna untuk akses cepat.
- **Fitur Khusus:**
  - **Pencarian Real-Time:** Kolom pencarian di bagian atas untuk menyaring daftar font secara langsung sewaktu mengetik.
  - **Penerapan Parsial:** Jika pengguna memilih sebagian teks di kotak input, font hanya akan diterapkan pada teks yang dipilih saja menggunakan `CustomTypefaceSpan`.

### C. Color (Warna)
*Tersedia untuk Text Layer & Shape Layer.*
Mengatur warna dasar elemen visual dengan beberapa opsi input:
- **Alat Pipet Warna (Eyedropper):** Memilih warna secara akurat langsung dari piksel mana pun di kanvas atau gambar latar belakang (*background*).
- **Palet Warna Cepat:** Menampilkan daftar warna siap pakai yang populer.
- **Color Picker Roda Warna & Kotak:** Dialog tingkat lanjut yang menyediakan slider Hue, Saturation, Value, serta slider Opasitas (Alpha).
- **Kolom Teks Kode Hex:** Mendukung pengetikan manual kode hex 6 digit (`#RRGGBB`) untuk warna solid penuh, dan otomatis berubah menjadi 8 digit (`#AARRGGBB`) jika mendeteksi warna transparan (Alpha < 255).

### D. Format (Pemformatan)
*Hanya tersedia untuk Text Layer.*
Berfungsi mengontrol detail layout dan estetika visual huruf yang terbagi dalam dua sub-tab:
1. **Formatting Tab:**
   - **Gaya Huruf (Styles):** Tebal (**B**), Miring (*I*), Garis Bawah (_U_), dan Coret Tengah (~~S~~). Mendukung penerapan parsial pada blok teks yang dipilih.
   - **Perataan (Alignment):** Rata Kiri, Rata Tengah, Rata Kanan, dan Rata Kanan-Kiri (*Justify*).
   - **Pencerminan (Mirroring):** Memutarbalikkan posisi layer secara horizontal maupun vertikal.
2. **Size Tab:**
   - **Text Size (Ukuran Teks):** Mengubah ukuran poin huruf (*font size*) dengan tombol presisi (+/-) atau secara parsial pada seleksi.
   - **Box Scale (Skala Wadah):** Mengubah skala pembesaran layer dalam persentase.
   - **Box Width (Lebar Wadah):** Mengatur batas lebar kolom aliran teks. Nilai "Auto" berarti lebar otomatis menyesuaikan panjang teks, sedangkan nilai angka membatasi lebar wadah agar teks turun ke baris berikutnya.
   - **Rotate (Rotasi):** Memutar sudut layer secara presisi derajat demi derajat.

### E. Spacing (Jarak Antar Elemen)
*Hanya tersedia untuk Text Layer.*
Mengatur kelonggaran spasial teks guna meningkatkan keterbacaan (readability).
- **Letter Spacing (Jarak Huruf):** Mengatur kerning atau kerapatan antarkarakter secara real-time. Nilai dapat dinaikkan atau diturunkan dengan tombol presisi per 0.01f. Mendukung penerapan parsial pada teks yang diblok.
- **Line Spacing (Jarak Baris):** Mengatur jarak vertikal antarbaris teks (leading) per 5.0f piksel untuk penataan paragraf yang seimbang.

### F. Stroke (Garis Tepi Pertama)
*Tersedia untuk Text Layer & Shape Layer.*
Menambahkan garis luar (outline) di sekeliling teks atau bentuk untuk memberikan kontras ekstra dari latar belakang.
- **Warna Stroke:** Pilihan warna solid lengkap dengan alat pipet, roda warna, atau palet cepat.
- **Ketebalan Stroke (Stroke Width):** Tombol presisi (+/-) untuk mengatur ketebalan garis tepi dalam satuan piksel.

### G. Double Stroke (Garis Tepi Kedua / Ganda)
*Tersedia untuk Text Layer & Shape Layer.*
Menyediakan fitur garis tepi lapis kedua (lapisan luar dari stroke pertama) untuk menciptakan efek teks bergaya pop atau retro yang dramatis.
- **Warna Double Stroke:** Dapat diatur terpisah dari warna stroke pertama.
- **Ketebalan Ganda (Double Stroke Width):** Menentukan seberapa lebar garis luar kedua membungkus stroke pertama.

### H. Shadow (Efek Bayangan)
*Tersedia untuk Text Layer & Shape Layer.*
Menghadirkan opsi efek bayangan yang sangat kustomisasi melalui dua tab pilihan:
1. **Drop Shadow (Bayangan Jatuh):**
   - **Warna Bayangan:** Mendukung warna solid maupun warna gradasi.
   - **Radius Blur:** Mengatur kelembutan buram tepi bayangan.
   - **DX & DY:** Menggeser offset bayangan secara horizontal (X) dan vertikal (Y).
   - **Tombol Center:** Mengembalikan offset DX & DY ke posisi tengah (0) secara instan.
2. **Motion Shadow (Bayangan Gerak):**
   - Menghasilkan bayangan solid bertingkat yang meniru efek ekstrusi 3D atau efek gerak berkecepatan tinggi.
   - **Blur Angle:** Sudut arah jatuhnya bayangan gerak (0° - 360°).
   - **Blur Distance:** Panjang bentangan ekstrusi bayangan (0 - 200).
   - **Thickness (Ketebalan):** Slider untuk mengontrol kerapatan/ketebalan iterasi bayangan gerak agar terlihat mulus atau bergaris kasar.
   - **Include Stroke (Sertakan Stroke):** Jika dicentang, bayangan gerak akan turut menduplikasi profil warna stroke layer teks/bentuk (mengabaikan nilai alpha asli stroke agar bayangan tetap terlihat solid).

### I. Gradation (Warna Gradasi)
*Tersedia untuk Text Layer & Shape Layer.*
Menggantikan warna solid layer dengan gradasi warna linier interaktif yang memukau.
- **Mode Gradasi (Gradation Mode):**
  - **Non-Aktif:** Menggunakan warna solid bawaan layer.
  - **Aktif:** Menampilkan garis arah panduan interaktif langsung di kanvas dengan handle titik awal dan titik akhir yang dapat digeser manual menggunakan jari.
- **Target Penerapan Gradasi:**
  - **Text/Fill:** Gradasi warna diterapkan pada isi/fill teks atau bentuk.
  - **Stroke:** Gradasi warna diterapkan pada stroke pertama.
  - **Shadow:** Gradasi warna diterapkan pada bayangan.
- **Warna Gradasi (Start & End Color):** Memungkinkan pemilihan warna awal dan akhir secara bebas.
- **Sudut Gradasi (Gradient Angle):** Slider alternatif untuk menentukan arah kemiringan gradasi jika tidak menggunakan panduan interaktif di kanvas.

### J. Texture & Pattern (Tekstur & Pola Bawaan)
*Tersedia untuk Text Layer & Shape Layer.*
Mengisi bagian dalam teks atau bentuk dengan gambar eksternal maupun pola berulang (seamless pattern).
- **Opsi Impor Tekstur:** Pengguna dapat mengimpor gambar pilihan sendiri dari galeri untuk dijadikan pola pengisi (fill) pada layer.
- **Browser Pola Bawaan (Browse Patterns):** Menyediakan galeri bermacam-macam pola SVG mulus berulang yang dikelompokkan secara terstruktur di aset aplikasi:
  - Pola lingkaran (*1-circles*)
  - Pola garis diagonal (*2-diagonal-stripe*)
  - Pola bintik (*3-dots*)
  - Pola garis horizontal (*4-horizontal-stripe*)
  - Pola garis vertikal (*5-vertical-stripe*)
  - Pola kustom menarik lainnya seperti whitecarbon, subtle-patch, crosshatch, houndstooth, dll. (*6-other*)
- **Parameter Kustomisasi Pola Bawaan:**
  - **Pattern Color:** Mengubah warna guratan pola SVG secara dinamis.
  - **Intensity:** Slider untuk mengatur skala ukuran perulangan pola.
  - **Opacity:** Transparansi pola overlay di atas teks.
  - **Rotation:** Sudut putaran pola.
  - **Alat Pergeseran Tekstur (Shift Texture/Pattern):** Tombol arah mata angin (▲, ▼, ◄, ►) serta tombol Reset (R) untuk menggeser koordinat posisi tekstur di dalam layer secara tepat.

### K. Opacity & Blend Mode (Transparansi & Efek Campuran)
*Tersedia untuk Seluruh Jenis Layer (Text, Shape, Image).*
Mengontrol interaksi piksel warna layer dengan elemen visual di bawahnya.
- **Blend Mode:** Pilihan mode pencampuran warna tingkat lanjut:
  - `NORMAL` (Standar).
  - `OVERLAY` (Meningkatkan kontras berdasarkan kegelapan/keterangan bawah).
  - `ADD` (Menambahkan nilai warna, menghasilkan efek berpijar).
  - `MULTIPLY` (Mengalikan warna, menghasilkan efek lebih gelap).
  - `SCREEN` (Membalikkan warna lalu mengalikannya, menghasilkan efek lebih terang).
  - `DARKEN` (Memilih piksel yang lebih gelap antara atas dan bawah).
  - `LIGHTEN` (Memilih piksel yang lebih terang antara atas dan bawah).
- **Opasitas Global (Global Opacity):** Mengatur tingkat transparansi keseluruhan layer dari 0% hingga 100%.
- **Gradient Opacity (Transparansi Bergradasi):** Mengaktifkan gradasi transparansi linier pada layer.
  - **Left Alpha:** Opasitas pada titik awal gradasi transparansi.
  - **Right Alpha:** Opasitas pada titik akhir gradasi transparansi.
  - **Angle:** Sudut arah gradasi transparansi.

### L. Erase Layer (Penghapus Masker Layer)
*Tersedia untuk Seluruh Jenis Layer (Text, Shape, Image).*
Memungkinkan pengguna untuk menghapus bagian tertentu dari layer tanpa merusak data aslinya (non-destructive masking). Bagian yang dihapus dapat berupa detail teks, bentuk SVG, maupun layer foto.
- **Ukuran Kuas (Size):** Mengatur lebar diameter kuas penghapus.
- **Opasitas Kuas (Opacity):** Menentukan kepekatan penghapusan (penghapusan semi-transparan).
- **Kekerasan Kuas (Hardness):** Mengatur kelembutan tepi kuas (BlurMaskFilter). Nilai 100% menghasilkan tepi kuas yang sangat tajam, sedangkan nilai di bawahnya memberikan efek pudar yang halus.
- **Optimasi Kinerja (Dua Tingkat Cache):** Pengoperasian penghapusan pada layer teks menggunakan sistem cache ganda (`cleanContentCache` dan `erasedContentCache`). Hal ini memisahkan perhitungan tata letak teks yang berat dari gerakan kuas jari pengguna, sehingga proses penghapusan terasa sangat mulus tanpa lag sedikit pun.

### M. Warp (Efek Melengkung / Kisi 3D)
*Tersedia untuk Seluruh Jenis Layer (Text, Shape, Image).*
Mentransformasi bentuk datar layer ke dalam mesh tiga dimensi fleksibel menggunakan evaluasi matematika Permukaan Bezier (*Bezier Surface*).
- **Kustomisasi Kolom & Baris Kisi (Rows & Cols):** Pengguna dapat membagi layer ke dalam kisi-kisi titik kontrol (misal: 2x2, 3x3, atau lebih).
- **Manipulasi Titik Bebas:** Setiap titik perpotongan kisi di kanvas dapat ditarik secara bebas ke segala arah untuk membuat teks melengkung, bergelombang, mengikuti permukaan botol, atau distorsi ekstrem lainnya.
- **Tombol Reset Points:** Mengembalikan seluruh titik kisi warp ke koordinat semula secara instan.

### N. Perspective (Perspektif)
*Tersedia untuk Seluruh Jenis Layer (Text, Shape, Image).*
Meniru efek kedalaman ruang (3D depth) dengan cara menarik empat sudut luar layer.
- **Handle Sudut:** Menampilkan empat handle bulat di setiap sudut layer. Pengguna dapat menggeser salah satu sudut untuk memberikan kesan teks atau gambar yang menjauh/mendekat, sangat berguna untuk menempelkan teks pada dinding bangunan atau jalan raya dalam gambar perspektif.
- **Tombol Reset:** Mengembalikan bentuk layer ke bentuk persegi datar semula.

### O. Effects (Efek Spesial Visual)
*Tersedia untuk Text Layer & Shape Layer.*
Menyediakan koleksi efek visual berkualitas tinggi. Pada perangkat modern dengan API 33+ (Android 13 ke atas), efek kompleks diproses dengan akselerasi perangkat keras menggunakan **AGSL RuntimeShader**, sedangkan perangkat di bawahnya otomatis menggunakan fungsi fallback perangkat lunak (software rendering) agar hasil visual tetap konsisten.

Sistem mendukung penerapan **dua efek sekaligus secara bersamaan** (efek primer dan efek sekunder).

Daftar Efek Spesial:
1. **Chromatic Aberration (Penyimpangan Warna):**
   - Meniru efek pembiasan lensa kamera 3D dengan memisahkan saluran warna merah, hijau, dan biru (RGB).
   - **Opsi Palet Khusus:** Menyediakan palet warna pergeseran siap pakai seperti Standar, Standar 2, Melancholy, Thriller, Romantic, Action, dan Nostalgia, serta dukungan kustomisasi manual ketiga warna tersebut.
   - **Shift Slider:** Jarak pemisahan antar warna.
2. **Glitch (Efek Kerusakan Sinyal):**
   - Memotong-motong layer secara horizontal menjadi barisan slice acak lalu menggesernya guna menciptakan visual distorsi digital.
   - **Intensity Slider:** Menentukan seberapa jauh slice teks bergeser secara acak.
   - **Randomize Seed Button:** Mengubah pola acak potongan glitch secara instan.
3. **Pixelation (Pikselasi):**
   - Mengelompokkan piksel layer ke dalam ukuran blok besar untuk menciptakan efek retro 8-bit / 16-bit.
   - **Block Size Slider:** Ukuran dari blok pikselasi.
4. **Neon (Efek Berpijar):**
   - Menghasilkan efek pendaran lampu neon terang di sekeliling teks atau bentuk.
   - **Glow Radius Slider:** Ketebalan pendaran cahaya.
   - **Glow Color:** Warna dari pendaran cahaya neon.
5. **Long Shadow (Bayangan Ekstrusi Panjang):**
   - Bayangan 3D panjang solid miring dengan pengaturan **Length** (panjang ekstrusi), **Angle** (sudut kemiringan), dan kustomisasi **Warna Bayangan**.
6. **Fiery (Efek Api Membakar):**
   - Efek animasi api yang berkobar ke atas dari permukaan teks atau bentuk.
   - **Intensity Slider:** Tinggi kobaran api acak.
   - **Fire Color:** Pilihan warna kobaran api kustom.
7. **Wavy (Efek Gelombang Cair):**
   - Memberikan efek distorsi air atau gelombang sinusoidal halus.
   - **Intensity Slider:** Amplitudo atau kedalaman lekukan gelombang.
   - **Frequency Slider:** Jumlah kerapatan gelombang pada objek.
8. **Gaussian Blur (Sensor Buram):**
   - Mengaburkan seluruh bagian layer secara merata.
   - **Blur Strength Slider:** Tingkat kekaburan objek.
9. **Radial Blur (Buram Melingkar / Efek Zoom Cepat):**
   - Memberikan efek buram memutar atau memancar keluar dari titik tengah objek (meniru gerakan kamera zoom dinamis).
   - **Clear Area (Inner Radius):** Menentukan batas diameter area tengah yang tetap terlihat tajam tanpa terkena buram.
   - **Motion Strength:** Menentukan kekuatan pusaran/pancaran efek buram.
10. **Halftone (Efek Cetak Koran Jadul):**
    - Mengonversi warna layer menjadi jajaran titik-titik pola raster cetak komik retro.
    - **Dot Size Slider:** Mengatur diameter titik-titik raster.
    - **Dot Color:** Warna titik pola raster.
11. **Multi Gradient (Gradasi Pelangi Kompleks):**
    - Membungkus objek dengan gradasi banyak warna sekaligus (hingga 7 kombinasi warna).
    - **Pilihan Palet Gradasi:** Rainbow, Sunset, Cyberpunk, Gold, dan Cotton Candy.
    - **Angle Slider:** Menentukan kemiringan sudut gradasi warna-warni tersebut.
12. **Text Decay (Erosi/Efek Pelapukan):**
    - Menciptakan efek lapuk, terkikis, atau hancur menjadi debu yang dimulai dari tepi luar karakter huruf menggunakan kebisingan pseudo-random multi-oktaf acak.
    - **Intensity Slider:** Tingkat keparahan erosi/pelapukan teks.
    - **Fading Level:** Kelembutan transisi ke bagian yang terkikis.
    - **Randomize Seed Button:** Mengacak ulang titik pelapukan.

---

## 4. MODE PENGEDITAN LANJUTAN (Advanced Editing)
Astral Typer dilengkapi dengan beberapa mode kanvas interaktif tingkat tinggi yang sangat berguna untuk merestorasi atau memanipulasi gambar dasar:

### A. Inpainting (Penghapus Objek Berbasis AI & Tradisional)
Mode ini digunakan untuk menghapus objek yang tidak diinginkan dari gambar latar belakang (misalnya menghapus balon teks komik asli agar dapat diganti dengan teks baru bahasa lain).
- **Cara Kerja:** Pengguna mengusapkan jari pada objek di gambar latar belakang untuk membuat masker merah (*inpaint mask*), lalu menekan tombol **APPLY**.
- **Dua Pilihan Mesin Pengolah (Inpaint Engines):**
  1. **OpenCV (Telea):** Algoritma inpainting tradisional berbasis difusi matematika yang cepat, efisien, dan bekerja tanpa memerlukan koneksi internet atau beban berat memori perangkat.
  2. **LaMa AI (AI Processor):** Jika file model AI telah diunduh, sistem akan menggunakan jaringan saraf tiruan (neural network) canggih untuk merekonstruksi latar belakang secara otomatis dengan hasil tekstur yang jauh lebih mulus dan natural.
- **Koleksi Alat Inpaint (Inpaint Toolbar):**
  - **Size Slider:** Menyesuaikan diameter sapuan kuas.
  - **Tombol Text (Auto-Mask):** Jika model AI detektor teks terpasang, tombol ini akan otomatis mendeteksi seluruh teks pada gambar latar belakang dan membuat masker penutupnya secara instan dalam sekali ketukan.
  - **Tool Brush/Eraser:** Kuas menggambar masker dan penghapus masker merah.
  - **Tool Lasso/Touch:** Memungkinkan pembuatan masker cepat dengan melingkari area objek (*Lasso*) atau mode sentuh biasa (*Touch*).

### B. Cut Mode (Pemotongan Gambar Interaktif)
Mode khusus untuk memotong area tertentu dari layer gambar terpilih menggunakan sapuan kuas masker.
- **Fungsi:** Berguna untuk mengambil subjek foto atau memisahkan karakter dari latar belakang gambar secara manual.
- **Pencegahan Leak Antarmuka:** Memiliki proteksi internal (*early-return guard*) untuk mencegah duplikasi tombol kontrol 'APPLY CUT' jika ikon ditekan berkali-kali secara tidak sengaja.

---

## 5. FITUR TYPER MODE (Batch Typesetting)
**Typer Mode** adalah fitur andalan revolusioner yang dirancang khusus bagi para komik typesetter untuk menata balon teks dalam jumlah banyak secara efisien tanpa perlu membuat layer satu per satu secara manual.

### Cara Menggunakan & Komponen Utama:
1. **Pengimporan Naskah (Batch Input):**
   - Pengguna dapat mengimpor dokumen teks `.txt` atau menempelkan (*paste*) baris teks langsung melalui dialog input khusus. Teks akan dipisahkan per baris secara otomatis.
2. **Daftar Alir Typer (Batch List):**
   - Baris-baris teks yang diimpor akan ditampilkan dalam bentuk daftar mengambang (*RecyclerView*) di bagian bawah.
   - Pengguna dapat memilih gaya teks kustom yang telah disimpan sebelumnya untuk diterapkan ke baris teks tersebut.
3. **Deteksi Balon Otomatis (Auto-Bubble Detection):**
   - Dengan memanfaatkan detektor visual berbasis model klasifikasi citra, sistem dapat menganalisis gambar latar belakang dan mendeteksi balon komik yang kosong secara otomatis.
   - Balon komik yang terdeteksi akan ditandai dengan lingkaran/oval merah transparan di kanvas.
4. **Penempatan Sekali Ketuk (Instan Flow):**
   - Pengguna cukup mengetuk lingkaran penanda balon di kanvas.
   - Baris teks aktif dari daftar Typer akan langsung meluncur masuk dan berubah menjadi Text Layer tepat di tengah balon tersebut!
   - Aliran teks secara otomatis dioptimalkan:
     - Jika teks panjang, ukuran wadah teks (*boxWidth*) diatur cerdas agar muat di dalam rasio balon komik.
     - Bentuk aliran teks diatur otomatis mengikuti kontur balon (persegi atau oval).
     - Skala teks otomatis mengecil jika teks padat guna mencegah teks keluar melewati batas balon komik.
   - Setelah ditempatkan, daftar alir Typer otomatis bergeser ke baris teks berikutnya untuk penempatan balon selanjutnya.
5. **Floating Tools Sidebar (Bilah Alat Mengambang):**
   - Menampilkan tombol melayang di sudut kiri kanvas untuk membantu navigasi navigasi:
     - **Hand Tool:** Geser kanvas secara bebas tanpa khawatir tidak sengaja memilih layer.
     - **Rect & Circle Tool:** Membuat balon buatan jika deteksi otomatis terlewat.
     - **Lasso Tool:** Membuat balon berbentuk bentuk tidak beraturan.
     - **Eraser Tool:** Menghapus penanda balon yang terdeteksi jika tidak ingin digunakan.

---

## 6. MANAJEMEN PROJECT & FILE (ProjectManager)
Sistem penyimpanan proyek yang sangat aman, rapi, dan mudah dikelola.

- **Penyimpanan Berkas .atd:** Proyek disimpan dalam format arsip terkompresi `.atd` yang di dalamnya membungkus metadata struktur layer dalam format JSON (`project.json`), gambar latar belakang asli, gambar mini (*thumbnail*), serta masker penghapusan layer.
- **Struktur Folder Hierarkis:** Mendukung pembuatan folder baru di dalam menu proyek, mengubah nama folder, navigasi rekursif (folder di dalam folder), dan pemindahan proyek antar folder.
- **Autosave (Penyimpanan Otomatis Pintar):**
  - Pengguna dapat mengaktifkan atau menonaktifkan fitur autosave melalui pengaturan.
  - Jika aktif, aplikasi akan otomatis menyimpan kemajuan proyek ketika beralih ke background atau saat aktivitas jeda (*onPause*).
  - Sistem memiliki mekanisme rotasi autosave cerdas yang hanya menyimpan 3 berkas cadangan autosave terbaru saja untuk mencegah penumpukan sampah file di memori penyimpanan perangkat.
- **Keamanan Memori (Bitmap Recycling):** Seluruh proses muat dan simpan proyek dilengkapi dengan fungsi pembebasan memori (*Bitmap recycling*) secara eksplisit untuk mencegah masalah kebocoran memori (memory leak) maupun kemacetan aplikasi (ANR) pada perangkat RAM rendah.

---

## 7. FITUR EKSPOR & FORMAT OUTPUT
Aplikasi menawarkan berbagai metode ekspor hasil karya berkualitas tinggi:

1. **Ekspor Gambar:**
   - Menyediakan pilihan format keluaran: **PNG** (transparansi penuh), **JPG** (ukuran ringkas), atau **WEBP** (teknologi kompresi gambar modern).
   - Menyediakan slider pengaturan kualitas gambar keluaran (0% - 100%) sebelum proses ekspor dijalankan.
2. **Ekspor Folder Multi-Halaman ke PDF (Custom PDF Writer):**
   - Fitur unggulan untuk mengonversi seluruh file proyek di dalam satu folder menjadi satu dokumen buku PDF multi-halaman yang rapi.
   - **Optimasi Ukuran Berkas Ekstrim:** Penulis PDF kustom Kotlin ini melewati pustaka `android.graphics.pdf.PdfDocument` bawaan Android (yang biasanya menghasilkan file PDF berukuran sangat besar karena kompresi tanpa rugi Flate/Zlib Skia). Sistem Astral Typer mengompresi setiap piksel halaman menjadi byte JPEG padat berkualitas tinggi, lalu menyematkannya secara langsung ke dalam aliran biner PDF menggunakan tag kompresi `/Filter /DCTDecode`.
   - **Skala Dinamis Sesuai Kualitas:** Resolusi gambar halaman akan dihitung secara dinamis dari skala 30% hingga 100% dari lebar dasar 1080px bergantung pada pengaturan kualitas PDF yang dipilih pengguna, menghemat ukuran penyimpanan file secara kuadratik tanpa kehilangan ketajaman visual teks.

---

## 8. PENGATURAN GLOBAL & WATERMARK
Melalui menu **SettingsActivity**, pengguna dapat menyesuaikan preferensi aplikasi secara global:

- **Autosave Toggle:** Checkbox untuk mengaktifkan atau menonaktifkan fitur penyimpanan otomatis secara global.
- **Kualitas PDF (PDF Quality Slider):** Slider (0-100) beserta indikator teks untuk menentukan tingkat kompresi dokumen PDF hasil ekspor (disimpan di SharedPreferences `"settings_prefs"` dengan kata kunci `"pdf_quality"`, default: 90).
- **Fitur Watermark Global (Tanda Air):**
  - **Enable Watermark:** Tombol pengaktifan tanda air pada setiap proyek.
  - **Watermark Opacity:** Slider tingkat transparansi tanda air (0 - 255).
  - **Auto Watermark:** Menentukan apakah tanda air otomatis ditempelkan langsung saat mengimpor gambar baru ke editor.
  - **Watermark Position:** Memilih 10 variasi penempatan tanda air pada halaman kanvas:
    - *Upper left* (Kiri Atas)
    - *Top* (Atas Tengah)
    - *Upper Right* (Kanan Atas)
    - *Middle Left* (Kiri Tengah)
    - *Center* (Tengah)
    - *Middle Right* (Kanan Tengah)
    - *Bottom Left* (Kiri Bawah)
    - *Bottom* (Bawah Tengah)
    - *Bottom Right* (Kanan Bawah)
    - *Random* (Koordinat acak berganti-ganti di kanvas)
  - **Watermark Image File:** Pengguna dapat mengimpor satu foto logo kustom yang akan disalin sebagai file internal `"watermark.png"` sebagai tanda air permanen.
