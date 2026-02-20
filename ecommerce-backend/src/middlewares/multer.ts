import multer from "multer";
import { v4 as uuid } from "uuid";

const storage = multer.diskStorage({
  destination(req, file, callback) {
    const date = new Date();
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");

    const path = `uploads/${year}/${month}/${day}`;
    
    // Create directory if it doesn't exist
    import("fs").then((fs) => {
        fs.mkdir(path, { recursive: true }, (err) => {
             if (err) return callback(err, path);
             callback(null, path);
        });
    });
  },

  filename(req, file, callback) {
    const id = uuid();
    const extName = file.originalname.split(".").pop();
    callback(null, `${id}.${extName}`);
  },
});

export const singleUpload = multer({ storage }).single("photo");
