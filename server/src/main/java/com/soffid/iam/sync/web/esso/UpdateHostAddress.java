package com.soffid.iam.sync.web.esso;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.soffid.iam.service.NetworkService;
import com.soffid.iam.sync.ServerServiceLocator;

import es.caib.seycon.ng.exception.UnknownNetworkException;


public class UpdateHostAddress extends HttpServlet {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpdateHostAddress.class);

    protected void doPost(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html; charset=UTF-8");
        try {
            String name = request.getParameter("name");
            String serial = request.getParameter("serial");
            String ip = request.getRemoteAddr();
            try {
                String nameIp = InetAddress.getByName(name).getHostAddress();
                if (! ip.equals(nameIp)) {
                    log.warn (String.format("Trying to register host %s(%s) from address %s",
                            name, nameIp, ip));
//                    response.getOutputStream().println("ERROR");
//                    return ;
                }
            } catch (UnknownHostException e) {
            
            }
            NetworkService xs = ServerServiceLocator.instance().getNetworkService();
            xs.registerDynamicIP(name, request.getRemoteAddr(), serial);
            response.getOutputStream().println("OK");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getOutputStream().println("ERROR|"+e.toString());
            if (! (e instanceof UnknownNetworkException))
            	log.warn("Error invoking " + request.getRequestURI(), e);
        }
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse response)
            throws ServletException, IOException {
        doPost(req, response);
    }
}