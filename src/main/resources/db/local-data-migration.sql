UPDATE delivery SET status = 'SHIPPED' WHERE CAST(status AS VARCHAR) = 'COMP';
