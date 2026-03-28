'use strict';

/**
 * Navigation controller.
 */
angular.module('docs').controller('Navigation', function($scope, $state, $stateParams, $rootScope, User) {
  User.userInfo().then(function(data) {
    $rootScope.userInfo = data;
    if (data.anonymous) {
      if($state.current.name !== 'login') {
        $state.go('login', {
          redirectState: $state.current.name,
          redirectParams: JSON.stringify($stateParams),
        }, {
          location: 'replace'
        });
      }
    }
  });

  /**
   * User logout.
   */
  $scope.logout = function($event) {
    User.logout().then(function(response) {
      User.userInfo(true).then(function(data) {
        $rootScope.userInfo = data;
      });
      if (response.logout_url) {
        window.location.href = response.logout_url;
      } else {
        $state.go('main');
      }
    });
    $event.preventDefault();
  };
});