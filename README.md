# jStbImageDecoder


Usage

```java
StbImage stbImage = new StbImage(ByteBuffer::allocate);
try{
    StbDecoder decoder = stbImage.getDecoder(ByteBuffer.wrap(input), false);
    StbImageInfo info = decoder.info();
    StbImageResult result;
    if(info.is16Bit()){
        result = decoder.load16(4);
    } else{
        result = decoder.load(4);
    }
    ByteBuffer data = result.getData();
}catch(Exception e){
    System.err.println("File not supported!");
}
```