CAMPUS LOST & FOUND (Java)

1. Install JDK 11 or newer (https://adoptium.net).
2. Keep this folder structure:
     CampusLostFound/
       LostFoundServer.java
       public/index.html
3. Open a terminal in the CampusLostFound folder and run:
     javac LostFoundServer.java
     java LostFoundServer
4. Open http://localhost:8080 in your browser.
5. Other people on the same Wi-Fi open http://YOUR-PC-IP:8080
   (find your IP with "ipconfig" on Windows or "ifconfig" on Mac/Linux).
6. Owner password for the Suggestion box inbox is admin123.
   Change it in LostFoundServer.java (ADMIN_KEY) before real use.
Data is saved in the "data" folder next to the program.

DEPLOYING FROM GITHUB
GitHub only stores the code; it cannot run a Java server.
1. Upload all files in this folder to a GitHub repository.
2. On render.com (or Railway / Fly.io) create a Web Service from that repository.
   It finds the Dockerfile automatically.
3. Set an environment variable ADMIN_KEY to your own host password.
4. For posts and photos to survive restarts, attach a persistent disk at /app/data.
