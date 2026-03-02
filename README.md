# Sony JPG Cookbook - In Camera LUT support
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuchanan)

This is very much just a proof of concept at this point to see what it possible. Right now the app reduces the size of the image to 6mb and converts a cube32 to cube16 to be able to process. Even then, it's slow... but IT WORKS!

If you'd like to try this out for yourself you need to make sure you have a folder named LUTs on the root of your SD card, that's where the app will pull your luts from to use.

In the app, toggle your navigate through options on the screen by pressing down and scroll wheel lets you make changes, such as toggling your lut. Then, snap your photo! Right now, you have to press UP on the cursor to "Bake" in the lut, be patient and when finished you should see this in a folder called COOKED inside of your typical images folder (usually 100MSDCF).
